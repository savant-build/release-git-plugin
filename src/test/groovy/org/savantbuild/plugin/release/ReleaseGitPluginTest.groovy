/*
 * Copyright (c) 2014, Inversoft Inc., All Rights Reserved
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package org.savantbuild.plugin.release

import org.savantbuild.dep.domain.Artifact
import org.savantbuild.dep.domain.ArtifactMetaData
import org.savantbuild.dep.domain.License
import org.savantbuild.dep.domain.Publication
import org.savantbuild.dep.domain.Version
import org.savantbuild.dep.workflow.PublishWorkflow
import org.savantbuild.dep.workflow.process.SVNProcess
import org.savantbuild.domain.Project
import org.savantbuild.io.FileTools
import org.savantbuild.output.Output
import org.savantbuild.output.SystemOutOutput
import org.testng.annotations.BeforeMethod
import org.testng.annotations.BeforeSuite
import org.testng.annotations.Test

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import static org.testng.Assert.assertEquals
import static org.testng.Assert.assertTrue

/**
 * Tests the ReleaseGitPlugin class.
 *
 * @author Brian Pontarelli
 */
class ReleaseGitPluginTest {
  public static Path projectDir

  Output output

  Project project

  ReleaseGitPlugin plugin

  @BeforeSuite
  public static void beforeSuite() {
    projectDir = Paths.get("")
    if (!Files.isRegularFile(projectDir.resolve("LICENSE"))) {
      projectDir = Paths.get("../release-git-plugin")
    }
  }

  @BeforeMethod
  public void beforeMethod() {
    output = new SystemOutOutput(true)
    output.enableDebug()

    project = new Project(projectDir.resolve("build/test/release/git-repo"), output)
    project.group = "org.savantbuild.test"
    project.name = "release-git-plugin-test"
    project.version = new Version("1.0")
    project.license = License.Apachev2

    plugin = new ReleaseGitPlugin(project, output)
  }

  @Test
  public void release() throws Exception {
    FileTools.prune(projectDir.resolve("build/test/release"))
    Files.createDirectories(projectDir.resolve("build/test/release/git-remote-repo"))
    Files.createDirectories(projectDir.resolve("build/test/release/git-repo"))

    // Create the git remote repository
    Path gitRemoteDir = projectDir.resolve("build/test/release/git-remote-repo").toRealPath()
    "git init --bare ${gitRemoteDir}".execute().waitFor()

    // Create a second git repository (the project) and make the first repository a remote
    Path gitDir = projectDir.resolve("build/test/release/git-repo").toRealPath()
    "git init ${gitDir}".execute().waitFor()
    "git remote add origin ${gitRemoteDir.toUri()}".execute([], gitDir.toFile())

    // Create an SVN repository for publishing
    Path svnDir = projectDir.resolve("build/test/release/svn-repo")
    "svnadmin create ${svnDir}".execute().waitFor()
    svnDir = svnDir.toRealPath()
    project.publishWorkflow = new PublishWorkflow(new SVNProcess(output, svnDir.toUri().toString(), null, null))

    // Create the publications and the files
    Path mainPub = gitDir.resolve("main-pub.txt")
    Files.write(mainPub, "Main Pub".getBytes())
    Path mainPubSource = gitDir.resolve("main-pub-source.txt")
    Files.write(mainPubSource, "Main Pub Source".getBytes())
    Path testPub = gitDir.resolve("test-pub.txt")
    Files.write(testPub, "Test Pub".getBytes())
    Path testPubSource = gitDir.resolve("test-pub-source.txt")
    Files.write(testPubSource, "Test Pub Source".getBytes())
    "git add main-pub.txt".execute([], gitDir.toFile()).waitFor()
    "git add main-pub-source.txt".execute([], gitDir.toFile()).waitFor()
    "git add test-pub.txt".execute([], gitDir.toFile()).waitFor()
    "git add test-pub-source.txt".execute([], gitDir.toFile()).waitFor()
    "git commit -am Test".execute([], gitDir.toFile()).waitFor()
    "git push -u origin master".execute([], gitDir.toFile()).waitFor()

    Publication mainPublication = new Publication(
        new Artifact("org.savantbuild.test:release-git-plugin-test:release-git-plugin-main:1.0:jar", License.Commercial),
        new ArtifactMetaData(project.dependencies, License.Commercial),
        mainPub,
        mainPubSource
    )
    Publication testPublication = new Publication(
        new Artifact("org.savantbuild.test:release-git-plugin-test:release-git-plugin-test:1.0:jar", License.Commercial),
        new ArtifactMetaData(project.dependencies, License.Commercial),
        testPub,
        testPubSource
    )
    project.publications.publicationGroups.put("main", [mainPublication])
    project.publications.publicationGroups.put("test", [testPublication])

    // Run the release
    plugin.release()

    // Verify the tag
    String output = "git tag -l".execute([], gitDir.toFile()).text
    assertTrue(output.contains("1.0"))

    // Verify the tag is pushed
    output = "git tag -l".execute([], gitRemoteDir.toFile()).text
    assertTrue(output.contains("1.0"))

    // Verify the publications are published
    output = "svn list ${svnDir.toUri()}org/savantbuild/test/release-git-plugin-test/1.0.0".execute().text
    println "svn list ${svnDir.toUri()}org/savantbuild/test/release-git-plugin-test/1.0.0"
    println "svn output is ${output}"
    assertTrue(output.contains("release-git-plugin-main-1.0.0.jar"))
    assertTrue(output.contains("release-git-plugin-main-1.0.0.jar.md5"))
    assertTrue(output.contains("release-git-plugin-main-1.0.0.jar.amd"))
    assertTrue(output.contains("release-git-plugin-main-1.0.0.jar.amd.md5"))
    assertTrue(output.contains("release-git-plugin-main-1.0.0-src.jar"))
    assertTrue(output.contains("release-git-plugin-main-1.0.0-src.jar.md5"))
    assertTrue(output.contains("release-git-plugin-test-1.0.0.jar"))
    assertTrue(output.contains("release-git-plugin-test-1.0.0.jar.md5"))
    assertTrue(output.contains("release-git-plugin-test-1.0.0.jar.amd"))
    assertTrue(output.contains("release-git-plugin-test-1.0.0.jar.amd.md5"))
    assertTrue(output.contains("release-git-plugin-test-1.0.0-src.jar"))
    assertTrue(output.contains("release-git-plugin-test-1.0.0-src.jar.md5"))

    // Check out the files from SVN and verify their contents
    Path svnVerify = projectDir.resolve("build/test/release/svn-verify")
    Files.createDirectories(svnVerify)
    "svn co ${svnDir.toUri()} ${svnVerify}".execute().text
    assertEquals(new String(Files.readAllBytes(svnVerify.resolve("org/savantbuild/test/release-git-plugin-test/1.0.0/release-git-plugin-main-1.0.0.jar"))), "Main Pub")
    assertEquals(new String(Files.readAllBytes(svnVerify.resolve("org/savantbuild/test/release-git-plugin-test/1.0.0/release-git-plugin-main-1.0.0-src.jar"))), "Main Pub Source")
    assertEquals(new String(Files.readAllBytes(svnVerify.resolve("org/savantbuild/test/release-git-plugin-test/1.0.0/release-git-plugin-test-1.0.0.jar"))), "Test Pub")
    assertEquals(new String(Files.readAllBytes(svnVerify.resolve("org/savantbuild/test/release-git-plugin-test/1.0.0/release-git-plugin-test-1.0.0-src.jar"))), "Test Pub Source")
  }
}
