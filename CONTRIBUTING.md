# Contributing

Changes reach `main` only through pull requests. Before a pull request can merge, the `gradle` and `docker` checks must pass, the branch must be up to date with `main`, and every review thread must be resolved. The [Development](README.md#development) section of the README covers building, testing, the public API check and the quality gates. This file covers how a change reaches `main`.

## The change process

Every change goes through these steps, in order:

1. **Plan.** Write down what changes and why, which files it touches, the decisions it makes, and how it will be verified. For a public API change, also cover what goes into `api/fake-llm.api` and `CHANGELOG.md`.
2. **Review the plan.** Someone other than the plan's author reviews it independently, against the code and primary sources (release notes, upstream source, specifications), not memory. Fold the findings in.
3. **Approval.** The maintainer approves the plan before any code is written.
4. **Build.** Implement the plan, with tests, and run the verification it describes.
5. **Review the diff.** An independent reviewer checks the diff against the approved plan and the code.
6. **Pull request.** Open the PR with a description of what changed, why, how it was verified, and what the reviews found. Watch CI and fix anything red.
7. **Merge.** Merge only after the maintainer's go-ahead, once CI is green.
8. **Clean up.** Delete the branch, both locally and on GitHub. Remove any scratch worktrees and temporary files the work created. Cleanup is part of finishing a change, not a separate request.

### Review findings and follow-up rounds

Every review round, of the plan or of the diff, labels each finding with evidence, in one of two tiers:

- **Blocking:** *blocker*, *critical* or *major*.
- **Non-blocking:** *should-change*, *minor* or *nit*.

A fix to a blocking finding is new, unreviewed work, so it gets another independent review round. That round covers what changed since the last reviewed version, plus anything the changes affect. Rounds repeat until one finds nothing blocking. When a round's findings are all non-blocking, fix the ones worth fixing and move on: no further round is needed.

## Branches

- **Name:** `<type>/<what-it-does>`, in lower case with hyphens, for example `feature/per-engine-scenario-directory`, `bug/stream-error-status`, `ci/basic-gradle-cache`, `deps/gradle-9` or `docs/contributing-process`.
- **Types:** `feature`, `bug`, `ci`, `deps`, `docs` and `chore`.
- **One branch per change:** start it from the latest `main`, and never reuse a branch after its pull request merges.
- **Dependabot** names its own branches, and its pull requests must pass the same checks.

## Commits

- **Merge commits:** by convention, pull requests are merged with a merge commit, not squashed or rebased, so every commit on the branch lands on `main`. The ruleset does not enforce this.
- **Each commit builds and passes the checks on its own,** since each one lands on `main`.
- **Separate commits for separate concerns:** a mechanical change, such as reformatting or a regenerated file, goes in its own commit, apart from the change it supports.
- **The message says what changed and why,** and refers to the issue when there is one.
