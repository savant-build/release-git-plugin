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

import org.savantbuild.domain.Project
import org.savantbuild.output.Output
import org.savantbuild.plugin.dep.DependencyPlugin
import org.savantbuild.plugin.groovy.BaseGroovyPlugin
import org.savantbuild.util.Graph

import java.nio.file.Files
import java.nio.file.Path

/**
 * Release git plugin. This releases a project that is maintained in a Git repository.
 *
 * @author Brian Pontarelli
 */
class ReleaseGitPlugin extends BaseGroovyPlugin {

  ReleaseGitPlugin(Project project, Output output) {
    super(project, output)
    new DependencyPlugin(project, output)
  }

  void release() {
    // Check if this is a working copy
    Path gitDirectory = project.directory.resolve(".git")
    if (!Files.isDirectory(gitDirectory)) {
      fail("You can only run a release from a Git repository.")
    }

    Git git = new Git(project.directory)
    updateGitAndCheckWorkingCopy(git)
    checkIfTagIsAvailable(git)
    checkDependenciesForIntegrationVersions()
    checkPluginsForIntegrationVersions()
    tag(git)
    publish()
  }

  private void publish() {
    project.publications.allPublications().each({ publication ->
      project.dependencyService.publish(publication, project.publishWorkflow)
    })
  }

  private void tag(Git git) {
    Process process = git.tag(project.version, "Release version [${project.version}].")
    if (process.exitValue() != 0) {
      fail("Unable to create Git tag for the release. Git output is:\n\n%s", process.text)
    }
  }

  private void checkPluginsForIntegrationVersions() {
    project.plugins.each({ dependency, plugin ->
      if (dependency.version.isIntegration()) {
        fail("Your project depends on the integration version of the plugin [%s]. You cannot depend on integration " +
            "builds of plugins when releasing a project.", dependency)
      }
    })
  }

  private void checkDependenciesForIntegrationVersions() {
    if (!project.artifactGraph) {
      return
    }

    project.artifactGraph.traverse(project.artifactGraph.root, true, { origin, destination, edge, depth ->
      if (destination.version.isIntegration()) {
        fail("Your project contains a dependency on the artifact [%s] which is an integration release. " +
            "You cannot depend on any integration releases when releasing a project.", destination)
      }
    } as Graph.GraphConsumer)
  }

  private void checkIfTagIsAvailable(Git git) {
    Process process = git.fetchTags()
    if (process.exitValue() != 0) {
      fail("Unable to fetch new tags from the remote git repository. Unable to perform a release.")
    }

    if (git.doesTagExist(project.version)) {
      fail("It appears that the version [%s] has already been released.", project.version)
    }
  }

  private Git updateGitAndCheckWorkingCopy(Git git) {
    // Do a pull
    output.info("Updating working copy")
    Process process = git.pull()
    if (process.exitValue() != 0) {
      fail("Unable to pull from remote Git repository. Unable to perform a release. Git output is:\n\n%s", process.text)
    }

    // See if the working copy is ahead
    process = git.status("-sb")
    if (process.exitValue() != 0) {
      fail("Unable to check the status of your local git repository. Unable to perform a release. Git output is:\n\n%s", process.text)
    }

    String status = process.text.trim()
    if (status.toLowerCase().contains("ahead")) {
      fail("Your git working copy appears to have local changes that haven't been pushed. Unable to perform a release.")
    }

    // Check for local modifications
    process = git.status("--porcelain")
    if (process.exitValue() != 0) {
      fail("Unable to check the status of your local git repository. Unable to perform a release. Git output is:\n\n%s", process.text)
    }

    status = process.text.trim()
    if (!status.isEmpty()) {
      fail("Cannot release from a dirty directory. Git status output is:\n\n%s", status)
    }
  }
}
