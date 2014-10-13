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

import java.nio.file.Path

/**
 * Wrap for git commands. This executes git directly and does not use a Git library. Therefore, Git must be in the users
 * PATH.
 *
 * @author Brian Pontarelli
 */
class Git {
  private final Path projectDirectory

  Git(Path projectDirectory) {
    this.projectDirectory = projectDirectory
  }

  /**
   * Performs a "git pull" command and returns the Process. This waits for the process to complete. The caller can check
   * the Process for the exit code and any output.
   *
   * @return The Process.
   */
  Process pull() {
    Process process = "git pull".execute([], projectDirectory.toFile())
    process.waitFor()
    return process
  }

  /**
   * Performs a "git status" command and returns the Process. This waits for the process to complete. The caller can check
   * the Process for the exit code and any output.
   *
   * @return The Process.
   */
  Process status(options) {
    Process process = "git status ${options}".execute([], projectDirectory.toFile())
    process.waitFor()
    return process
  }

  /**
   * Fetches the new tags from the remote repository by performing a "git fetch -t". This waits for the process to
   * complete. The caller can check the Process for the exit code and any output.
   *
   * @return The Process.
   */
  Process fetchTags() {
    Process process = "git fetch -t".execute([], projectDirectory.toFile())
    process.waitFor()
    return process
  }

  /**
   * Determines if the given tag exists in the local repository.
   *
   * @param tagName The tag name.
   * @return True if the tag exists, false if it doesn't.
   * @throws RuntimeException If the "git tag -l" command fails.
   */
  boolean doesTagExist(tagName) throws RuntimeException {
    Process process = "git tag -l ${tagName}".execute([], projectDirectory.toFile())
    process.waitFor()
    if (process.exitValue() != 0) {
      throw new RuntimeException("Unable to list the git tags.")
    }

    String output = process.text
    return (output != null && output.length() > 0)
  }

  /**
   * Creates a remote tag. This is a two part process and if the first step (create the local tag) fails, then this
   * throws a RuntimeException.
   *
   * @param tagName The tag to create.
   * @param comment The comment used in the commit for the tag.
   * @return The Process for the second step (pushing the tags to the remote).
   */
  Process tag(tagName, comment) throws RuntimeException {
    Process process = ["git", "tag", "-a", tagName, "-m", comment].execute([], projectDirectory.toFile())
    process.waitFor()
    if (process.exitValue() != 0) {
      throw new RuntimeException("Unable to create the tag [${tagName}] in the local git repository. Output is [${process.text}].")
    }

    process = "git push --tags".execute([], projectDirectory.toFile())
    process.waitFor()
    return process
  }
}
