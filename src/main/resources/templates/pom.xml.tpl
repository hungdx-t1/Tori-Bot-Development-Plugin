<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>{{GROUP_ID}}</groupId>
    <artifactId>{{NAME}}</artifactId>
    <version>{{VERSION}}</version>
    <packaging>jar</packaging>

    <name>{{NAME}}</name>

    <properties>
        <maven.compiler.source>{{JAVA_VERSION}}</maven.compiler.source>
        <maven.compiler.target>{{JAVA_VERSION}}</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <tori.version>{{TORI_VERSION}}</tori.version>
    </properties>

    <build>
        <finalName>{{NAME}}</finalName>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.13.0</version>
                <configuration>
                    <source>{{JAVA_VERSION}}</source>
                    <target>{{JAVA_VERSION}}</target>
                </configuration>
            </plugin>

            <!-- keep this, do not remove -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-shade-plugin</artifactId>
                <version>3.6.0</version>
                <executions>
                    <execution>
                        <phase>package</phase>
                        <goals>
                            <goal>shade</goal>
                        </goals>
                        <configuration>
                            <createDependencyReducedPom>false</createDependencyReducedPom>
                            <transformers>
                                <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                                    <manifestEntries>
                                        <Multi-Release>true</Multi-Release>
                                    </manifestEntries>
                                </transformer>
                                <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
                            </transformers>
                            <filters>
                                <filter>
                                    <artifact>*:*</artifact>
                                    <excludes>
                                        <exclude>META-INF/*.SF</exclude>
                                        <exclude>META-INF/*.DSA</exclude>
                                        <exclude>META-INF/*.RSA</exclude>
                                        <exclude>META-INF/LICENSE*</exclude>
                                        <exclude>META-INF/NOTICE*</exclude>
                                        <exclude>META-INF/DEPENDENCIES</exclude>
                                        <exclude>META-INF/MANIFEST.MF</exclude>
                                        <exclude>module-info.class</exclude>
                                        <exclude>META-INF/versions/**/module-info.class</exclude>
                                    </excludes>
                                </filter>
                            </filters>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>

    <repositories>
        <repository>
            <id>jitpack</id>
            <url>https://jitpack.io</url>
        </repository>
    </repositories>

    <dependencies>
        <dependency>
            <groupId>com.github.hungdx-t1.Tori</groupId>
            <artifactId>tori-api</artifactId>
            <version>${tori.version}</version>
            <scope>provided</scope>
        </dependency>
        {{DAVE_DEPENDENCY}}
    </dependencies>
</project>
