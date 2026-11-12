package com.dianxin.tori.intellij.creator

import com.intellij.icons.AllIcons
import com.intellij.ide.util.projectWizard.ModuleBuilder
import com.intellij.ide.util.projectWizard.ModuleWizardStep
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.ide.wizard.AbstractNewProjectWizardStep
import com.intellij.ide.wizard.GeneratorNewProjectWizard
import com.intellij.ide.wizard.GitNewProjectWizardStep
import com.intellij.ide.wizard.NewProjectWizardBaseData.Companion.baseData
import com.intellij.ide.wizard.NewProjectWizardBaseStep
import com.intellij.ide.wizard.NewProjectWizardChainStep.Companion.nextStep
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.ide.wizard.RootNewProjectWizardStep
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemJdkUtil
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.module.ModuleType
import com.intellij.openapi.module.ModuleTypeManager
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.JavaSdkType
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkTypeId
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.startup.StartupManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.validation.DialogValidation
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.io.HttpRequests
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.jetbrains.plugins.gradle.settings.GradleSettings
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.Icon
import javax.swing.JComponent

// ======================================================================
// ================ CONFIG ==============================================
// ======================================================================

enum class BuildSystem(val label: String) {
    GRADLE("Gradle (Kotlin DSL)"),
    MAVEN("Maven");

    override fun toString() = label
}

/** Dữ liệu người dùng nhập ở wizard. */
class ToriProjectConfig {
    var buildSystem: BuildSystem = BuildSystem.GRADLE
    var groupId: String = "com.example"
    var mainClassName: String = "MyBot"
    var version: String = "1.0-SNAPSHOT"
    var description: String = ""
    var author: String = System.getProperty("user.name") ?: ""
    var ownerId: String = "0000000000000000"
    var useDave: Boolean = true

    /** "latest" = tự lấy bản mới nhất từ GitHub (có fallback). */
    var toriVersion: String = "latest"

    /** Được điền bất đồng bộ bởi wizard step. */
    @Volatile
    var resolvedLatest: String? = null
}

// ======================================================================
// ================ UTILITY CLASSES =====================================
// ======================================================================

object ToriVersionResolver {
    const val FALLBACK = "26.10.0"

    private const val API = "https://api.github.com/repos/hungdx-t1/Tori/releases/latest"

    /** Gọi GitHub API; lỗi mạng / không có release thì trả về [FALLBACK]. */
    fun fetchLatest(): String = try {
        val json = HttpRequests.request(API)
            .accept("application/vnd.github+json")
            .connectTimeout(4000)
            .readTimeout(4000)
            .readString()
        Regex("\"tag_name\"\\s*:\\s*\"v?([^\"]+)\"").find(json)?.groupValues?.get(1) ?: FALLBACK
    } catch (e: Exception) {
        FALLBACK
    }
}

/** Ghi file vào VFS (gọi trong write action). Chỉ dùng bởi wizard kiểu cũ. */
internal object VfsWriter {
    fun write(root: VirtualFile, relativePath: String, content: String): VirtualFile {
        val parts = relativePath.split('/')
        var dir = root
        for (p in parts.dropLast(1)) {
            dir = dir.findChild(p) ?: dir.createChildDirectory(this, p)
        }
        val name = parts.last()
        val file = dir.findChild(name) ?: dir.createChildData(this, name)
        VfsUtil.saveText(file, content)
        return file
    }

    fun ensureDir(root: VirtualFile, relativePath: String): VirtualFile {
        var dir = root
        for (p in relativePath.split('/')) {
            dir = dir.findChild(p) ?: dir.createChildDirectory(this, p)
        }
        return dir
    }
}

// ======================================================================
// ================ PROJECT GENERATOR ===================================
// ======================================================================

/**
 * Sinh nội dung các file của project từ template trong resources/templates.
 * Placeholder có dạng {{NAME}} (tránh đụng với $var của Gradle / ${prop} của Maven).
 */
object ToriProjectGenerator {

    /** Tên file metadata của bot. Đổi ở đây nếu Tori server yêu cầu tên khác. */
    const val BOT_YML_FILE = "bot.yml"

    fun generate(config: ToriProjectConfig, projectName: String): Map<String, String> {
        val artifact = projectName.replace(Regex("[^A-Za-z0-9_.-]"), "-").ifBlank { "MyBot" }
        val pkgSuffix = projectName.lowercase().replace(Regex("[^a-z0-9]"), "")
            .let { if (it.isEmpty() || it[0].isDigit()) "bot$it" else it }
        val pkg = "${config.groupId}.$pkgSuffix"

        val toriVersion = when (config.toriVersion.trim().lowercase()) {
            "", "latest" -> config.resolvedLatest ?: ToriVersionResolver.FALLBACK
            else -> config.toriVersion.trim()
        }

        val vars = mapOf(
            "NAME" to artifact,
            "DISPLAY_NAME" to yaml(projectName),
            "GROUP_ID" to config.groupId,
            "VERSION" to config.version,
            "PACKAGE" to pkg,
            "MAIN_CLASS" to config.mainClassName,
            "DESC" to yaml(config.description.ifBlank { "$projectName - Tori bot" }),
            "AUTHOR" to yaml(config.author),
            "OWNER_ID" to config.ownerId,
            "TORI_VERSION" to toriVersion,
            "USE_DAVE" to config.useDave.toString(),
            "JAVA_VERSION" to if (config.useDave) "25" else "21",
            "DAVE_DEPENDENCY" to if (config.useDave) DAVE_POM_DEPENDENCY else "",
        )

        val files = linkedMapOf<String, String>()
        val pkgPath = pkg.replace('.', '/')
        files["src/main/java/$pkgPath/${config.mainClassName}.java"] = render("Bot.java.tpl", vars)
        files["src/main/resources/$BOT_YML_FILE"] = render("bot.yml.tpl", vars)
        files[".gitignore"] = render("gitignore.tpl", vars)

        when (config.buildSystem) {
            BuildSystem.GRADLE -> {
                files["build.gradle.kts"] = render("build.gradle.kts.tpl", vars)
                files["settings.gradle.kts"] = render("settings.gradle.kts.tpl", vars)
            }
            BuildSystem.MAVEN -> files["pom.xml"] = render("pom.xml.tpl", vars)
        }
        return files
    }

    private fun render(template: String, vars: Map<String, String>): String {
        val raw = ToriProjectGenerator::class.java.getResourceAsStream("/templates/$template")
            ?.readBytes()?.toString(Charsets.UTF_8)
            ?: error("Thiếu template: $template")
        return vars.entries.fold(raw) { acc, (k, v) -> acc.replace("{{$k}}", v) }
    }

    /** Bọc chuỗi trong dấu nháy kép nếu chứa ký tự đặc biệt của YAML. */
    private fun yaml(s: String): String =
        if (s.matches(Regex("[A-Za-z0-9 _.,()-]*"))) s
        else "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private val DAVE_POM_DEPENDENCY = """
        <dependency>
            <groupId>com.github.hungdx-t1.Tori</groupId>
            <artifactId>tori-extension-dave</artifactId>
            <version>${'$'}{tori.version}</version>
            <scope>provided</scope> <!-- Tori server with Dave already provides it -->
        </dependency>
    """.trimIndent().prependIndent("        ").trimStart()
}

// ======================================================================
// ================ NEW PROJECT WIZARD (đang được đăng ký) ==============
// ======================================================================

/**
 * New Project Wizard (kiểu mới): Name / Location / Git / Group / Artifact / JDK
 * nằm chung một trang như project Java của IntelliJ.
 */
class ToriNewProjectWizard : GeneratorNewProjectWizard {

    override val id: String = "TORI_BOT"

    override val name: String = "Tori Bot"

    override val icon: Icon = AllIcons.Nodes.Plugin

    override fun createStep(context: WizardContext): NewProjectWizardStep =
        RootNewProjectWizardStep(context)
            .nextStep(::NewProjectWizardBaseStep)   // Name + Location
            .nextStep(::GitNewProjectWizardStep)    // Create Git repository
            .nextStep(::ToriStep)                   // Group, Artifact, JDK, bot info...
}

private class ToriStep(parent: NewProjectWizardStep) : AbstractNewProjectWizardStep(parent) {

    private val config = ToriProjectConfig()

    /** Artifact tự theo Name cho tới khi người dùng sửa tay. */
    private val artifactProperty = propertyGraph.lazyProperty { baseData?.name ?: "" }

    private var sdk: Sdk? = null

    init {
        baseData?.nameProperty?.let { nameProp ->
            artifactProperty.dependsOn(nameProp) { nameProp.get() }
        }
        // Lấy trước bản Tori mới nhất ở nền để không chặn UI.
        ApplicationManager.getApplication().executeOnPooledThread {
            config.resolvedLatest = ToriVersionResolver.fetchLatest()
        }
    }

    override fun setupUI(builder: Panel) {
        val ident = "[a-zA-Z_][a-zA-Z0-9_]*"

        with(builder) {
            row("Build system:") {
                comboBox(BuildSystem.values().toList())
                    .bindItem({ config.buildSystem }, { config.buildSystem = it ?: BuildSystem.GRADLE })
            }
            row("Group:") {
                textField().bindText(config::groupId).align(AlignX.FILL)
                    .validationOnApply(
                        DialogValidation { if (!config.groupId.matches(Regex("$ident(\\.$ident)*"))) error("GroupId không hợp lệ (vd: com.example)") else null }
                    )
            }
            row("Artifact:") {
                textField().bindText(artifactProperty).align(AlignX.FILL)
            }
            row("JDK:") {
                val javaSdks = ProjectJdkTable.getInstance().getSdksOfType(JavaSdk.getInstance())
                sdk = javaSdks.firstOrNull { context.projectJdk?.name == it.name } ?: javaSdks.firstOrNull()
                comboBox(
                    CollectionComboBoxModel(javaSdks),
                    SimpleListCellRenderer.create<Sdk>("") { it.name }
                ).bindItem({ sdk }, { sdk = it }).align(AlignX.FILL)
            }
            group("Bot") {
                row("Main class:") {
                    textField().bindText(config::mainClassName).align(AlignX.FILL)
                        .validationOnApply(
                            DialogValidation { if (!config.mainClassName.matches(Regex(ident))) error("Tên main class không hợp lệ") else null }
                        )
                }
                row("Version:") {
                    textField().bindText(config::version).align(AlignX.FILL)
                }
                row("Description:") {
                    textField().bindText(config::description).align(AlignX.FILL)
                }
                row("Author:") {
                    textField().bindText(config::author).align(AlignX.FILL)
                }
                row("Owner ID:") {
                    textField().bindText(config::ownerId).align(AlignX.FILL)
                        .comment("User ID Discord của chủ bot")
                        .validationOnApply(
                            DialogValidation { if (!config.ownerId.matches(Regex("\\d+"))) error("Owner ID phải là dãy số") else null }
                        )
                }
            }
            group("Tori") {
                row("Tori version:") {
                    textField().bindText(config::toriVersion).align(AlignX.FILL)
                        .comment("\"latest\" = lấy bản mới nhất từ GitHub, lỗi mạng thì dùng ${ToriVersionResolver.FALLBACK}")
                }
                row {
                    checkBox("Dùng DAVE Protocol (tự nâng Java lên 25)").bindSelected(config::useDave)
                }
            }
        }
    }

    override fun setupProject(project: Project) {
        val base = baseData ?: return
        val root: Path = Path.of(base.path).resolve(base.name)
        val artifact = artifactProperty.get().ifBlank { base.name }

        ToriProjectGenerator.generate(config, artifact).forEach { (relative, text) ->
            val file = root.resolve(relative)
            Files.createDirectories(file.parent)
            Files.writeString(file, text)
        }

        val rootVfs = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root) ?: return
        VfsUtil.markDirtyAndRefresh(false, true, true, rootVfs)

        sdk?.let { chosen ->
            WriteAction.runAndWait<RuntimeException> {
                ProjectRootManager.getInstance(project).projectSdk = chosen
            }
        }

        StartupManager.getInstance(project).runAfterOpened {
            ApplicationManager.getApplication().invokeLater {
                try {
                    when (config.buildSystem) {
                        BuildSystem.GRADLE -> importGradle(project, root.toString())
                        BuildSystem.MAVEN -> rootVfs.findChild("pom.xml")?.let {
                            MavenProjectsManager.getInstance(project).addManagedFilesOrUnignore(listOf(it))
                        }
                    }
                } catch (e: Throwable) {
                    LOG.warn("Không thể tự import project Tori", e)
                }
            }
        }
    }

    private fun importGradle(project: Project, path: String) {
        val gradleSettings = GradleSettings.getInstance(project)
        if (gradleSettings.getLinkedProjectSettings(path) == null) {
            gradleSettings.linkProject(GradleProjectSettings().apply {
                externalProjectPath = path
                gradleJvm = ExternalSystemJdkUtil.USE_PROJECT_JDK
            })
        }
        ExternalSystemUtil.refreshProject(
            path,
            ImportSpecBuilder(project, GradleConstants.SYSTEM_ID)
                .use(ProgressExecutionMode.IN_BACKGROUND_ASYNC)
        )
    }

    companion object {
        private val LOG = Logger.getInstance(ToriStep::class.java)
    }
}

// ======================================================================
// ================ LEGACY WIZARD (ModuleBuilder, hiện KHÔNG đăng ký) ===
// ======================================================================

class ToriModuleType : ModuleType<ToriModuleBuilder>(ID) {

    override fun createModuleBuilder() = ToriModuleBuilder()

    override fun getName() = "Tori Bot"

    override fun getDescription() = "Tạo project Discord bot cho Tori Framework (JDA)"

    override fun getNodeIcon(isOpened: Boolean): Icon = AllIcons.Nodes.Plugin

    companion object {
        const val ID = "TORI_MODULE"

        fun instance(): ToriModuleType =
            ModuleTypeManager.getInstance().findByID(ID) as ToriModuleType
    }
}

class ToriModuleBuilder : ModuleBuilder() {

    val config = ToriProjectConfig()

    override fun getModuleType(): ModuleType<*> = ToriModuleType.instance()

    override fun getBuilderId() = "tori.bot.builder"

    override fun getPresentableName() = "Tori Bot"

    override fun getDescription() = "Tạo project Discord bot cho Tori Framework (JDA)"

    override fun getNodeIcon(): Icon = AllIcons.Nodes.Plugin

    override fun isSuitableSdkType(sdkType: SdkTypeId?) = sdkType is JavaSdkType

    override fun getCustomOptionsStep(context: WizardContext?, parentDisposable: Disposable?): ModuleWizardStep =
        ToriWizardStep(config)

    override fun setupRootModel(modifiableRootModel: ModifiableRootModel) {
        val sdk = moduleJdk
        if (sdk != null) modifiableRootModel.sdk = sdk else modifiableRootModel.inheritSdk()

        val contentEntry = doAddContentEntry(modifiableRootModel) ?: return
        val root = contentEntry.file ?: return
        val projectName = name ?: root.name

        ToriProjectGenerator.generate(config, projectName).forEach { (path, text) ->
            VfsWriter.write(root, path, text)
        }

        // Đăng ký source/resources root tạm thời (Gradle/Maven import sẽ ghi đè lại sau).
        VfsWriter.ensureDir(root, "src/main/java").let { contentEntry.addSourceFolder(it, false) }
        VfsWriter.ensureDir(root, "src/main/resources").let { contentEntry.addSourceFolder(it, false) }

        scheduleImport(modifiableRootModel.project, root)
    }

    private fun scheduleImport(project: Project, root: VirtualFile) {
        StartupManager.getInstance(project).runAfterOpened {
            ApplicationManager.getApplication().invokeLater {
                try {
                    when (config.buildSystem) {
                        BuildSystem.GRADLE -> importGradle(project, root)
                        BuildSystem.MAVEN -> root.findChild("pom.xml")?.let {
                            MavenProjectsManager.getInstance(project).addManagedFilesOrUnignore(listOf(it))
                        }
                    }
                } catch (e: Throwable) {
                    LOG.warn("Không thể tự import project Tori", e)
                }
            }
        }
    }

    private fun importGradle(project: Project, root: VirtualFile) {
        val path = root.path
        val gradleSettings = GradleSettings.getInstance(project)
        if (gradleSettings.getLinkedProjectSettings(path) == null) {
            val projectSettings = GradleProjectSettings().apply {
                externalProjectPath = path
            }
            gradleSettings.linkProject(projectSettings)
        }
        ExternalSystemUtil.refreshProject(
            path,
            ImportSpecBuilder(project, GradleConstants.SYSTEM_ID)
                .use(ProgressExecutionMode.IN_BACKGROUND_ASYNC)
        )
    }

    companion object {
        private val LOG = Logger.getInstance(ToriModuleBuilder::class.java)
    }
}

class ToriWizardStep(private val config: ToriProjectConfig) : ModuleWizardStep() {

    init {
        // Lấy trước bản Tori mới nhất ở nền để không chặn UI.
        ApplicationManager.getApplication().executeOnPooledThread {
            config.resolvedLatest = ToriVersionResolver.fetchLatest()
        }
    }

    private val panel: DialogPanel = panel {
        group("Project") {
            row("Build system:") {
                comboBox(BuildSystem.values().toList())
                    .bindItem({ config.buildSystem }, { config.buildSystem = it ?: BuildSystem.GRADLE })
            }
            row("GroupId:") {
                textField().bindText(config::groupId).align(AlignX.FILL)
            }
            row("Main class:") {
                textField().bindText(config::mainClassName).align(AlignX.FILL)
            }
            row("Version:") {
                textField().bindText(config::version).align(AlignX.FILL)
            }
        }
        group("Bot info") {
            row("Description:") {
                textField().bindText(config::description).align(AlignX.FILL)
            }
            row("Author:") {
                textField().bindText(config::author).align(AlignX.FILL)
            }
            row("Owner ID:") {
                textField().bindText(config::ownerId).align(AlignX.FILL)
                    .comment("User ID Discord của chủ bot")
            }
        }
        group("Tori") {
            row("Tori version:") {
                textField().bindText(config::toriVersion).align(AlignX.FILL)
                    .comment("\"latest\" = lấy bản mới nhất từ GitHub, lỗi mạng thì dùng ${ToriVersionResolver.FALLBACK}")
            }
            row {
                checkBox("Dùng DAVE Protocol (tự nâng Java lên 25)").bindSelected(config::useDave)
            }
        }
    }

    override fun getComponent(): JComponent = panel

    override fun updateDataModel() {
        panel.apply()
    }

    @Throws(ConfigurationException::class)
    override fun validate(): Boolean {
        panel.apply()
        val ident = "[a-zA-Z_][a-zA-Z0-9_]*"
        if (!config.groupId.matches(Regex("$ident(\\.$ident)*"))) {
            throw ConfigurationException("GroupId không hợp lệ (vd: com.example)", "Tori")
        }
        if (!config.mainClassName.matches(Regex(ident))) {
            throw ConfigurationException("Tên main class không hợp lệ", "Tori")
        }
        if (!config.ownerId.matches(Regex("\\d+"))) {
            throw ConfigurationException("Owner ID phải là dãy số (Discord user ID)", "Tori")
        }
        if (config.version.isBlank()) {
            throw ConfigurationException("Version không được để trống", "Tori")
        }
        return true
    }
}