# Syncing changes from upstream temporal-sdk-java

## Intro/local setup

This guide  assumes you have two remotes:
* `stash` pointing at stash/spkr/nflx-temporal-sdk
* `oss` pointing at github/temporalio/sdk-java

You should see something like:

```
$ git remote -v
stash    ssh://git@stash.corp.netflix.com:7999/spkr/nflx-temporal-sdk.git (fetch)
stash    ssh://git@stash.corp.netflix.com:7999/spkr/nflx-temporal-sdk.git (push)
oss      git@github.com:temporalio/sdk-java.git (fetch)
oss      git@github.com:temporalio/sdk-java.git (push)
```


In stash we have two primary branches:
* `ossmaster` this is just a mirror of what is at oss/master
* `master` this is our release branch where we have integrated our patches to OSS

We should never commit anything to ossmaster aside from syncing it from temporalio.
We'll just use this branch to generate merges into our master branch so it's a bit
easier to reason about in stash.


## Integrating changes from OSS

### 1. Sync changes from github/master to stash/ossmaster

```
$ git fetch -p stash
```

```
$ git checkout -B ossmaster stash/ossmaster
Branch 'ossmaster' set up to track remote branch 'ossmaster' from 'stash'.
Switched to and reset branch 'ossmaster'
Your branch is up to date with 'stash/ossmaster'.
```

```
$ git pull oss master
From github.com:temporalio/sdk-java
 * branch              master     -> FETCH_HEAD
Updating 51fc36c5..388c278b
Fast-forward
 temporal-sdk/build.gradle           |  2 +-
 temporal-serviceclient/build.gradle | 14 +++++++-------
 2 files changed, 8 insertions(+), 8 deletions(-)
```

```
$ git push
```

### 2. Create a PR to merge OSS changes to master

```
$ git fetch -p stash
```

```
$ git checkout -b merge_oss stash/master
Branch 'merge_oss' set up to track remote branch 'master' from 'stash'.
Switched to a new branch 'merge_oss'
```

```
$ git merge ossmaster
...
...
```

At this point you will likely hit some merge conflicts on gradle files
due to oss bumping versions and us having removed them in favor
of dependency recommender.

For gradle conflicts you should just be able to prefer our changes
over oss.

After all the conflicts are resolved and added:

```
$ git status
On branch merge_oss
Your branch is up to date with 'stash/master'.

All conflicts fixed but you are still merging.
  (use "git commit" to conclude merge)
```

```
$ git commit
```

```
$ git push -u stash merge_oss
Enumerating objects: 1, done.
Counting objects: 100% (1/1), done.
Writing objects: 100% (1/1), 903 bytes | 903.00 KiB/s, done.
Total 1 (delta 0), reused 0 (delta 0), pack-reused 0
remote:
remote: Create pull request for merge_oss:
remote:   https://stash.corp.netflix.com/projects/SPKR/repos/nflx-temporal-sdk/pull-requests?create&sourceBranch=refs/heads/merge_oss
remote:
Syncing changes from upstream temporal-sdk-java
remote: Build Jobs
remote: ----------
remote: nflx-temporal-sdk --> https://spinnaker.builds.test.netflix.net/job/nflx-temporal-sdk/
remote:
...
...
```

### 3. Open the PR in stash

### 4. Merge the PR using the 'Merge' strategy
This is going to leave a bit of a trail of merge commits in our history but I think it is the most obvious way of
keeping track of when exactly we synced changes across..

## Upgrade notes

In v1.0.8 new gRPC dependencies were added that are included in io.grpc v1.34.0+ which is a version
ahead of the latest Netflix supported version.  Fortunately, these dependencies were added for a new 
testing feature that is optional and also not fully baked, see 
https://github.com/temporalio/sdk-java/pull/470 for more details.

Where necessary, we have commented out that code and included a note with the above PR in it.  When
we are able to upgrade to Netflix grpc 1.34.0+ we can resolve this issue.  To find the code, search
for this comment:

```
// TODO requires io.grpc 1.34.0+ https://github.com/temporalio/sdk-java/pull/470
```
