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

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import org.savantbuild.dep.domain.Artifact
import org.savantbuild.dep.domain.ArtifactMetaData
import org.savantbuild.dep.domain.Dependencies
import org.savantbuild.dep.domain.DependencyGroup
import org.savantbuild.dep.domain.License
import org.savantbuild.dep.domain.Publication
import org.savantbuild.dep.domain.ReifiedArtifact
import org.savantbuild.dep.workflow.FetchWorkflow
import org.savantbuild.dep.workflow.PublishWorkflow
import org.savantbuild.dep.workflow.Workflow
import org.savantbuild.dep.workflow.process.CacheProcess
import org.savantbuild.dep.workflow.process.SVNProcess
import org.savantbuild.domain.Project
import org.savantbuild.domain.Version
import org.savantbuild.io.FileTools
import org.savantbuild.output.Output
import org.savantbuild.output.SystemOutOutput
import org.savantbuild.runtime.BuildFailureException
import org.savantbuild.runtime.RuntimeConfiguration
import org.testng.annotations.BeforeMethod
import org.testng.annotations.BeforeSuite
import org.testng.annotations.Test

import static org.testng.Assert.assertEquals
import static org.testng.Assert.assertFalse
import static org.testng.Assert.assertNotNull
import static org.testng.Assert.assertTrue
import static org.testng.Assert.fail

/**
 * Tests the ReleaseGitPlugin class.
 *
 * @author Brian Pontarelli
 */
class ReleaseGitPluginTest {
  public static Path projectDir

  Path gitDir

  Path gitRemoteDir

  Output output

  Project project

  Path svnDir

  Path mainPub

  Path mainPubSource

  Path testPub

  Path testPubSource

  Path cacheDir

  @BeforeSuite
  static void beforeSuite() {
    projectDir = Paths.get("")
    if (!Files.isRegularFile(projectDir.resolve("LICENSE"))) {
      projectDir = Paths.get("../release-git-plugin")
    }
  }

  @BeforeMethod
  void beforeMethod() {
    output = new SystemOutOutput(true)
    output.enableDebug()

    project = new Project(projectDir.resolve("build/test/release/git-repo"), output)
    project.group = "org.savantbuild.test"
    project.name = "release-git-plugin-test"
    project.version = new Version("1.0")
    project.licenses.add(License.parse("ApacheV2_0", null))

    cacheDir = projectDir.resolve("../savant-dependency-management/test-deps/savant")
    project.workflow = new Workflow(
        new FetchWorkflow(output, new CacheProcess(output, cacheDir.toString())),
        new PublishWorkflow(new CacheProcess(output, cacheDir.toString()))
    )

    FileTools.prune(projectDir.resolve("build/test/release"))
    Files.createDirectories(projectDir.resolve("build/test/release/git-remote-repo"))
    Files.createDirectories(projectDir.resolve("build/test/release/git-repo"))

    // Create the git remote repository
    gitRemoteDir = projectDir.resolve("build/test/release/git-remote-repo").toRealPath()
    "git init --bare ${gitRemoteDir}".execute().waitFor()

    // Create a second git repository (the project) and make the first repository a remote
    gitDir = projectDir.resolve("build/test/release/git-repo").toRealPath()
    "git init ${gitDir}".execute().waitFor()
    "git remote add origin ${gitRemoteDir.toUri()}".execute([], gitDir.toFile())

    // Create an SVN repository for publishing
    svnDir = projectDir.resolve("build/test/release/svn-repo")
    "svnadmin create ${svnDir}".execute().waitFor()
    svnDir = svnDir.toRealPath()
    project.publishWorkflow = new PublishWorkflow(new SVNProcess(output, svnDir.toUri().toString(), null, null))

    // Create the publications and the files
    mainPub = gitDir.resolve("main-pub.txt")
    Files.write(mainPub, "Main Pub".getBytes())
    mainPubSource = gitDir.resolve("main-pub-source.txt")
    Files.write(mainPubSource, "Main Pub Source".getBytes())
    testPub = gitDir.resolve("test-pub.txt")
    Files.write(testPub, "Test Pub".getBytes())
    testPubSource = gitDir.resolve("test-pub-source.txt")
    Files.write(testPubSource, "Test Pub Source".getBytes())
    "git add main-pub.txt".execute([], gitDir.toFile()).waitFor()
    "git add main-pub-source.txt".execute([], gitDir.toFile()).waitFor()
    "git add test-pub.txt".execute([], gitDir.toFile()).waitFor()
    "git add test-pub-source.txt".execute([], gitDir.toFile()).waitFor()
    "git commit -am Test".execute([], gitDir.toFile()).waitFor()
    "git push -u origin master".execute([], gitDir.toFile()).waitFor()
  }

  @Test
  void releaseCanNotPull() throws Exception {
    project.dependencies = null
    setupPublications(project, mainPub, mainPubSource, testPub, testPubSource)

    // Remove the git remote
    "git remote remove origin".execute([], gitDir.toFile())

    // Run the release
    try {
      ReleaseGitPlugin plugin = new ReleaseGitPlugin(project, new RuntimeConfiguration(), output)
      plugin.release()
      fail("Should have failed")
    } catch (e) {
      // Expected
      assertTrue(e.message.contains("Unable to pull from remote Git repository"))
    }

    assertReleaseDidNotRun()
  }

  @Test
  void releaseFromNonGitDirectory() throws Exception {
    project.dependencies = null
    setupPublications(project, mainPub, mainPubSource, testPub, testPubSource)

    // Setup the bad directory and recreate the plugin
    Files.createDirectories(projectDir.resolve("build/test/release/bad-project-dir"))
    project = new Project(projectDir.resolve("build/test/release/bad-project-dir"), output)

    // Run the release
    try {
      ReleaseGitPlugin plugin = new ReleaseGitPlugin(project, new RuntimeConfiguration(), output)
      plugin.release()
      fail("Should have failed")
    } catch (e) {
      // Expected
      assertTrue(e.message.contains("You can only run a release from a Git repository."))
    }

    assertReleaseDidNotRun()
  }

  @Test
  void releaseWithDependencyIntegrationBuild() throws Exception {
    project.dependencies = new Dependencies(
        new DependencyGroup("compile", true,
            new Artifact("org.savantbuild.test:intermediate:1.0.0")
        ),
        new DependencyGroup("test", false,
            new Artifact("org.savantbuild.test:leaf1:1.0.0")
        )
    )
    setupPublications(project, mainPub, mainPubSource, testPub, testPubSource)

    // Run the release
    ReleaseGitPlugin plugin = new ReleaseGitPlugin(project, new RuntimeConfiguration(), output)
    try {
      plugin.release()
      fail("Should have failed")
    } catch (BuildFailureException e) {
      assertTrue(e.message.contains("integration release"))
    }
  }

  @Test
  void releaseWithDependencies() throws Exception {
    project.dependencies = new Dependencies(
        new DependencyGroup("compile", true,
            new Artifact("org.savantbuild.test:leaf2:1.0.0")
        ),
        new DependencyGroup("test", false,
            new Artifact("org.savantbuild.test:leaf1:1.0.0")
        )
    )
    setupPublications(project, mainPub, mainPubSource, testPub, testPubSource)

    // Run the release
    ReleaseGitPlugin plugin = new ReleaseGitPlugin(project, new RuntimeConfiguration(), output)
    plugin.release()

    assertNotNull(project.artifactGraph)

    assertTagsExist()

    // Verify the SubVersion publish and the AMD files
    Path svnVerify = assertFilesPublishedToSVN()
    assertEquals(new String(Files.readAllBytes(svnVerify.resolve("org/savantbuild/test/release-git-plugin-test/1.0.0/release-git-plugin-main-1.0.0.jar.amd"))),
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<artifact-meta-data>\n" +
            "  <license type=\"Commercial\"><![CDATA[License]]></license>\n" +
            "  <dependencies>\n" +
            "    <dependency-group name=\"compile\">\n" +
            "      <dependency group=\"org.savantbuild.test\" project=\"leaf2\" name=\"leaf2\" version=\"1.0.0\" type=\"jar\"/>\n" +
            "    </dependency-group>\n" +
            "  </dependencies>\n" +
            "</artifact-meta-data>\n"
    )
    assertEquals(new String(Files.readAllBytes(svnVerify.resolve("org/savantbuild/test/release-git-plugin-test/1.0.0/release-git-plugin-test-1.0.0.jar.amd"))),
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<artifact-meta-data>\n" +
            "  <license type=\"Commercial\"><![CDATA[License]]></license>\n" +
            "  <dependencies>\n" +
            "    <dependency-group name=\"compile\">\n" +
            "      <dependency group=\"org.savantbuild.test\" project=\"leaf2\" name=\"leaf2\" version=\"1.0.0\" type=\"jar\"/>\n" +
            "    </dependency-group>\n" +
            "  </dependencies>\n" +
            "</artifact-meta-data>\n"
    )
  }

  @Test
  void releaseWithoutDependencies() throws Exception {
    project.dependencies = null
    setupPublications(project, mainPub, mainPubSource, testPub, testPubSource)

    // Run the release
    ReleaseGitPlugin plugin = new ReleaseGitPlugin(project, new RuntimeConfiguration(), output)
    plugin.release()

    assertTagsExist()

    // Verify the SubVersion publish and the AMD files
    Path svnVerify = assertFilesPublishedToSVN()
    assertEquals(new String(Files.readAllBytes(svnVerify.resolve("org/savantbuild/test/release-git-plugin-test/1.0.0/release-git-plugin-main-1.0.0.jar.amd"))),
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<artifact-meta-data>\n" +
            "  <license type=\"Commercial\"><![CDATA[License]]></license>\n" +
            "</artifact-meta-data>\n"
    )
    assertEquals(new String(Files.readAllBytes(svnVerify.resolve("org/savantbuild/test/release-git-plugin-test/1.0.0/release-git-plugin-test-1.0.0.jar.amd"))),
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<artifact-meta-data>\n" +
            "  <license type=\"Commercial\"><![CDATA[License]]></license>\n" +
            "</artifact-meta-data>\n"
    )
  }

  private
  static void setupPublications(Project project, Path mainPub, Path mainPubSource, Path testPub, Path testPubSource) {
    Publication mainPublication = new Publication(
        new ReifiedArtifact("org.savantbuild.test:release-git-plugin-test:release-git-plugin-main:1.0:jar", [License.parse("Commercial", "License")]),
        new ArtifactMetaData(project.dependencies, [License.parse("Commercial", "License")]),
        mainPub,
        mainPubSource
    )
    Publication testPublication = new Publication(
        new ReifiedArtifact("org.savantbuild.test:release-git-plugin-test:release-git-plugin-test:1.0:jar", [License.parse("Commercial", "License")]),
        new ArtifactMetaData(project.dependencies, [License.parse("Commercial", "License")]),
        testPub,
        testPubSource
    )
    project.publications.publicationGroups.put("main", [mainPublication])
    project.publications.publicationGroups.put("test", [testPublication])
  }

  private void assertTagsExist() {
    // Verify the tag exists
    String output = "git tag -l".execute([], gitDir.toFile()).text
    assertTrue(output.contains("1.0.0"))

    // Verify the tag is pushed
    output = "git tag -l".execute([], gitRemoteDir.toFile()).text
    assertTrue(output.contains("1.0.0"))
  }

  private Path assertFilesPublishedToSVN() {
    // Verify the publications are published
    String output = "svn list ${svnDir.toUri()}org/savantbuild/test/release-git-plugin-test/1.0.0".execute().text
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
    return svnVerify
  }

  private void assertReleaseDidNotRun() {
    // Verify the tags don't exist
    String output = "git tag -l".execute([], gitDir.toFile()).text
    assertFalse(output.contains("1.0.0"))

    // Verify the tag is pushed
    output = "git tag -l".execute([], gitRemoteDir.toFile()).text
    assertFalse(output.contains("1.0.0"))

    // Verify the publications are published
    output = "svn list ${svnDir.toUri()}org/savantbuild/test/release-git-plugin-test/1.0.0".execute().text
    assertFalse(output.contains("release-git-plugin-main-1.0.0.jar"))
    assertFalse(output.contains("release-git-plugin-main-1.0.0.jar.md5"))
    assertFalse(output.contains("release-git-plugin-main-1.0.0.jar.amd"))
    assertFalse(output.contains("release-git-plugin-main-1.0.0.jar.amd.md5"))
    assertFalse(output.contains("release-git-plugin-main-1.0.0-src.jar"))
    assertFalse(output.contains("release-git-plugin-main-1.0.0-src.jar.md5"))
    assertFalse(output.contains("release-git-plugin-test-1.0.0.jar"))
    assertFalse(output.contains("release-git-plugin-test-1.0.0.jar.md5"))
    assertFalse(output.contains("release-git-plugin-test-1.0.0.jar.amd"))
    assertFalse(output.contains("release-git-plugin-test-1.0.0.jar.amd.md5"))
    assertFalse(output.contains("release-git-plugin-test-1.0.0-src.jar"))
    assertFalse(output.contains("release-git-plugin-test-1.0.0-src.jar.md5"))
  }
}
