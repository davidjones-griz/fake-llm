# Notes for Claude

Follow [CONTRIBUTING.md](CONTRIBUTING.md) for every change:
1. a plan;
2. an independent plan review;
3. the maintainer's approval;
4. the build;
5. an independent diff review;
6. the pull request;
7. a merge only on the maintainer's go-ahead, once CI is green;
8. cleanup.

After any review round, plan or diff, that finds a blocker, critical or major issue, fix it, then run another independent review round on the fixes. Repeat until a round finds nothing blocking. A round whose findings are all should-change, minor or nit needs no follow-up.

- **Name branches** `<type>/<what-it-does>`; the types are listed in CONTRIBUTING.md.
- **Clean up without being asked:** remove scratch worktrees and temporary files when the work is done, delete local branches once their pull request merges, and remove the remote branch. If the session cannot delete a remote branch, say so in the summary rather than leaving it unmentioned.
- **Verify before claiming:** run CI's own commands locally (`./gradlew build koverHtmlReport --continue`, `./gradlew publishAllPublicationsToBuildLocalRepository`, `./gradlew -p examples check`) and report the results as they are.
