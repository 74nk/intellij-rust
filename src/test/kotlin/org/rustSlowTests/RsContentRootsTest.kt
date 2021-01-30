/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rustSlowTests

import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.VirtualFile
import org.rust.TestProject
import org.rust.cargo.RsWithToolchainTestBase
import org.rust.cargo.project.model.impl.testCargoProjects
import org.rust.fileTree
import org.rust.ide.module.RsModuleType

class RsContentRootsTest : RsWithToolchainTestBase() {

    fun `test project with subproject`() {
        val project = buildProject {
            toml("Cargo.toml", """
                [package]
                name = "intellij-rust-test"
                version = "0.1.0"
                authors = []

                [workspace]
                members = ["subproject"]
            """)
            dir("src") {
                rust("main.rs", "")
            }
            dir("examples") {}
            dir("tests") {}
            dir("benches") {}
            dir("target") {}

            dir("subproject") {
                toml("Cargo.toml", """
                    [package]
                    name = "subproject"
                    version = "0.1.0"
                    authors = []
                """)
                dir("src") {
                    rust("main.rs", "")
                }
                dir("examples") {}
                dir("tests") {}
                dir("benches") {}
                dir("target") {}
            }
        }

        val projectFolders = listOf(
            ProjectFolder.Source(project.findFile("src"), false),
            ProjectFolder.Source(project.findFile("examples"), false),
            ProjectFolder.Source(project.findFile("tests"), true),
            ProjectFolder.Source(project.findFile("benches"), true),
            ProjectFolder.Excluded(project.findFile("target")),
            ProjectFolder.Source(project.findFile("subproject/src"), false),
            ProjectFolder.Source(project.findFile("subproject/examples"), false),
            ProjectFolder.Source(project.findFile("subproject/tests"), true),
            ProjectFolder.Source(project.findFile("subproject/benches"), true),
            ProjectFolder.Excluded(project.findFile("subproject/target"))
        )

        check(myModule, projectFolders)
    }

    fun `test do not add non existing roots`() {
        val project = buildProject {
            toml("Cargo.toml", """
                [package]
                name = "intellij-rust-test"
                version = "0.1.0"
                authors = []
            """)
            dir("src") {
                rust("main.rs", "")
            }
            dir("tests") {}
            dir("target") {}
        }

        val projectFolders = listOf(
            ProjectFolder.Source(project.findFile("src"), false),
            ProjectFolder.Source(project.findFile("tests"), true),
            ProjectFolder.Excluded(project.findFile("target"))
        )

        check(myModule, projectFolders)
    }

    fun `test workspace without root package`() {
        val project = buildProject {
            toml("Cargo.toml", """
                [workspace]
                members = ["package1", "package2"]
            """)
            dir("target") {}

            dir("package1") {
                toml("Cargo.toml", """
                    [package]
                    name = "package1"
                    version = "0.1.0"
                    authors = []
                """)
                dir("src") {
                    rust("main.rs", "")
                }
                dir("examples") {}
                dir("tests") {}
                dir("benches") {}
                dir("target") {}
            }
            dir("package2") {
                toml("Cargo.toml", """
                    [package]
                    name = "package2"
                    version = "0.1.0"
                    authors = []
                """)
                dir("src") {
                    rust("main.rs", "")
                }
                dir("examples") {}
                dir("tests") {}
                dir("benches") {}
                dir("target") {}
            }
        }

        val projectFolders = listOf(
            ProjectFolder.Excluded(project.findFile("target")),

            ProjectFolder.Source(project.findFile("package1/src"), false),
            ProjectFolder.Source(project.findFile("package1/examples"), false),
            ProjectFolder.Source(project.findFile("package1/tests"), true),
            ProjectFolder.Source(project.findFile("package1/benches"), true),
            ProjectFolder.Excluded(project.findFile("package1/target")),

            ProjectFolder.Source(project.findFile("package2/src"), false),
            ProjectFolder.Source(project.findFile("package2/examples"), false),
            ProjectFolder.Source(project.findFile("package2/tests"), true),
            ProjectFolder.Source(project.findFile("package2/benches"), true),
            ProjectFolder.Excluded(project.findFile("package2/target"))
        )

        check(myModule, projectFolders)
    }

    fun `test several modules in workspace`() {
        val testProject = fileTree {
            toml("Cargo.toml", """
                [workspace]
                members = [
                    "a",
                    "b",
                ]
            """)

            dir("target") {}
            dir("a") {
                toml("Cargo.toml", """
                    [package]
                    name = "a"
                    version = "0.1.0"
                    authors = []

                    [dependencies]
                    b = { path = "../b" }
                """)
                dir("src") {
                    rust("lib.rs", "")
                }
                dir("examples") {}
                dir("tests") {}
                dir("benches") {}
                dir("target") {}
            }
            dir("b") {
                toml("Cargo.toml", """
                    [package]
                    name = "b"
                    version = "0.1.0"
                    authors = []
                """)
                dir("src") {
                    rust("lib.rs", "")
                }
                dir("examples") {}
                dir("tests") {}
                dir("benches") {}
                dir("target") {}
            }
        }.create(project, cargoProjectDirectory)

        val moduleA = createModule(testProject.findFile("a"))
        val moduleB = createModule(testProject.findFile("b"))

        project.testCargoProjects.discoverAndRefreshSync()

        check(myModule, listOf(ProjectFolder.Excluded(testProject.findFile("target"))))
        check(moduleA, listOf(
            ProjectFolder.Source(testProject.findFile("a/src"), false),
            ProjectFolder.Source(testProject.findFile("a/examples"), false),
            ProjectFolder.Source(testProject.findFile("a/tests"), true),
            ProjectFolder.Source(testProject.findFile("a/benches"), true),
            ProjectFolder.Excluded(testProject.findFile("a/target")),
        ))
        check(moduleB, listOf(
            ProjectFolder.Source(testProject.findFile("b/src"), false),
            ProjectFolder.Source(testProject.findFile("b/examples"), false),
            ProjectFolder.Source(testProject.findFile("b/tests"), true),
            ProjectFolder.Source(testProject.findFile("b/benches"), true),
            ProjectFolder.Excluded(testProject.findFile("b/target"))
        ))
    }

    private fun check(module: Module, projectFolders: List<ProjectFolder>) {
        ModuleRootModificationUtil.updateModel(module) { model ->
            val contentEntry = model.contentEntries.firstOrNull() ?: error("Can't find any content entry")
            val sourceFiles = contentEntry.sourceFolders.associateBy {
                it.file ?: error("Can't find file with `${it.url}`")
            }
            val excludedFiles = contentEntry.excludeFolders.associateBy {
                it.file ?: error("Can't find file with `${it.url}`")
            }

            for (projectFolder in projectFolders) {
                when (projectFolder) {
                    is ProjectFolder.Source -> {
                        val sourceFile = sourceFiles[projectFolder.file]
                            ?: error("Can't find `${projectFolder.file}` folder in source folders")
                        check(sourceFile.isTestSource == projectFolder.isTest)
                    }
                    is ProjectFolder.Excluded -> {
                        if (excludedFiles[projectFolder.file] == null) {
                            error("Can't find `${projectFolder.file}` folder in excluded folders")
                        }
                    }
                }
            }
        }
    }

    private fun TestProject.findFile(path: String): VirtualFile =
        root.findFileByRelativePath(path) ?: error("Can't find `$path` in `$root`")

    private fun createModule(contentRoot: VirtualFile): Module {
        val moduleManager = ModuleManager.getInstance(project)
        val module = runWriteAction { moduleManager.newModule(contentRoot.path, RsModuleType.ID) }
        ModuleRootModificationUtil.addContentRoot(module, contentRoot)
        return module
    }

    private sealed class ProjectFolder {
        data class Source(val file: VirtualFile, val isTest: Boolean) : ProjectFolder()
        data class Excluded(val file: VirtualFile) : ProjectFolder()
    }
}
