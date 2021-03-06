/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package git4idea.log

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.Consumer
import com.intellij.util.EmptyConsumer
import com.intellij.util.containers.ContainerUtil
import com.intellij.vcs.log.Hash
import com.intellij.vcs.log.TimedVcsCommit
import com.intellij.vcs.log.VcsCommitMetadata
import com.intellij.vcs.log.VcsLogTextFilter
import com.intellij.vcs.log.data.VcsLogData
import com.intellij.vcs.log.data.index.IndexDataGetter
import com.intellij.vcs.log.impl.VcsProjectLog
import com.intellij.vcs.log.util.BekUtil
import com.intellij.vcs.log.util.StopWatch
import git4idea.history.GitLogUtil
import java.util.regex.Pattern

internal class GitBekParentFixer private constructor(private val incorrectCommits: Set<Hash>) {

  fun fixCommit(commit: TimedVcsCommit): TimedVcsCommit {
    return if (!incorrectCommits.contains(commit.id)) commit
    else object : TimedVcsCommit by commit {
      override fun getParents(): List<Hash> = ContainerUtil.reverse(commit.parents)
    }
  }

  companion object {
    private val LOG = Logger.getInstance(GitBekParentFixer::class.java)

    @JvmStatic
    fun prepare(project: Project, root: VirtualFile): GitBekParentFixer {
      if (isEnabled()) {
        try {
          return GitBekParentFixer(getIncorrectCommits(project, root))
        }
        catch (e: VcsException) {
          LOG.warn("Could not find incorrect merges ", e)
        }
      }
      return GitBekParentFixer(emptySet())
    }

    @JvmStatic
    fun fixCommits(commits: List<VcsCommitMetadata>): List<VcsCommitMetadata> {
      if (!isEnabled()) return commits

      return commits.map map@{ commit ->
        if (commit.parents.size <= 1) return@map commit
        if (!MAGIC_FILTER.matches(commit.fullMessage)) return@map commit
        return@map object : VcsCommitMetadata by commit {
          override fun getParents(): List<Hash> = ContainerUtil.reverse(commit.parents)
        }
      }
    }
  }
}

private fun isEnabled() = BekUtil.isBekEnabled() && Registry.`is`("git.log.fix.merge.commits.parents.order")

private const val MAGIC_REGEX = "^Merge remote(\\-tracking)? branch '.*/(.*)'( into \\2)?$"
private val MAGIC_FILTER = object : VcsLogTextFilter {
  val pattern = Pattern.compile(MAGIC_REGEX, Pattern.MULTILINE)

  override fun matchesCase(): Boolean {
    return false
  }

  override fun isRegex(): Boolean {
    return false
  }

  override fun getText(): String {
    return "Merge remote"
  }

  override fun matches(message: String): Boolean {
    return pattern.matcher(message).find(0)
  }
}

@Throws(VcsException::class)
fun getIncorrectCommits(project: Project, root: VirtualFile): Set<Hash> {
  val dataManager = VcsProjectLog.getInstance(project).dataManager
  val dataGetter = dataManager?.index?.dataGetter
  if (dataGetter == null || !dataManager.index.isIndexed(root)) {
    return getIncorrectCommitsFromGit(project, root)
  }
  return getIncorrectCommitsFromIndex(dataManager, dataGetter, root)
}

fun getIncorrectCommitsFromIndex(dataManager: VcsLogData,
                                 dataGetter: IndexDataGetter,
                                 root: VirtualFile): MutableSet<Hash> {
  val stopWatch = StopWatch.start("getting incorrect merges from index for ${root.name}")
  val commits = dataGetter.filter(listOf(MAGIC_FILTER)).asSequence()
  val result = commits.map { dataManager.storage.getCommitId(it)!! }.filter { it.root == root }.mapTo(mutableSetOf()) { it.hash }
  stopWatch.report()
  return result
}

@Throws(VcsException::class)
fun getIncorrectCommitsFromGit(project: Project, root: VirtualFile): MutableSet<Hash> {
  val stopWatch = StopWatch.start("getting incorrect merges from git for ${root.name}")
  val filterParameters = ContainerUtil.newArrayList<String>()

  filterParameters.addAll(GitLogUtil.LOG_ALL)
  filterParameters.add("--merges")

  GitLogProvider.appendTextFilterParameters(MAGIC_REGEX, true, false, filterParameters)

  val result = mutableSetOf<Hash>()
  GitLogUtil.readTimedCommits(project, root, filterParameters, EmptyConsumer.getInstance(),
                              EmptyConsumer.getInstance(), Consumer { commit -> result.add(commit.id) })
  stopWatch.report()
  return result
}