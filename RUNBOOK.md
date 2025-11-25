# Viaduct OSS Infrastructure Runbook

This document explains how to administer the infrastructure used by the
Viaduct OSS project.

## Github

Administration is handled by Airbnb's open source committee.

### CI

We use Github Actions to run Viaduct's public [CI jobs](https://github.com/airbnb/viaduct/actions).

## Gradle Plugin Portal

Plugins are published via the `viaduct-maintainers` account owned by
Airbnb. https://plugins.gradle.org/u/viaduct-maintainers

## Maven Central/Sonatype

Access to Airbnb's Sonatype namespace is controlled via Airbnb's Github
organization. Only members of the Airbnb Github organization can access
the namespace.

## Copybara

Viaduct has dual homes: Github and Airbnb's internal monorepo. We use
[Copybara](https://github.com/google/copybara) to sync changes between
the two source trees. Copybara runs on internal Airbnb infrastructure
and is not accessible to outside contributors.

## How To

### Prerequisites for Release Managers

Before performing your first release, ensure you have the following setup:

#### Required Tools

1. **GitHub CLI (`gh`)**
   ```bash
   # macOS
   brew install gh

   # Verify installation
   gh --version
   ```

2. **Python 3**
   ```bash
   # Should be pre-installed on macOS
   python3 --version  # Should show 3.x
   ```

#### Initial Setup (One-time)

1. **Authenticate GitHub CLI:**
   ```bash
   gh auth login
   ```
   - Select: `GitHub.com`
   - Select: `HTTPS`
   - Authenticate via browser when prompted

2. **Verify SSH access to GitHub:**
   ```bash
   ssh -T git@github.com
   ```
   Expected output: `Hi <username>! You've successfully authenticated`

   If this fails:
   - Ensure you have SSH keys set up: https://docs.github.com/en/authentication/connecting-to-github-with-ssh
   - Add your key to ssh-agent: `ssh-add ~/.ssh/id_rsa`

3. **Clone public Viaduct repository:**
   ```bash
   # Clone to a separate directory (not inside Treehouse)
   git clone git@github.com:airbnb/viaduct.git ~/repos/viaduct-public
   cd ~/repos/viaduct-public
   ```

4. **[Optional] Set default repository:**

   This makes `gh` commands default to `airbnb/viaduct` so you don't need to specify `--repo` every time.

   ```bash
   # Run from within the cloned directory
   cd ~/repos/viaduct-public
   gh repo set-default airbnb/viaduct
   # When prompted, select: airbnb/viaduct
   ```

   **Why this is useful:** When you run commands like `gh workflow run` or `gh release list`, they'll automatically use `airbnb/viaduct` instead of requiring `--repo airbnb/viaduct` every time.

#### Required Access

Verify you have access to:
- [ ] Airbnb GitHub organization (check: https://github.com/orgs/airbnb/people)
- [ ] Gradle Plugin Portal `viaduct-maintainers` account (check: https://plugins.gradle.org/u/viaduct-maintainers)
- [ ] 1Password vault with `viaductbot` credentials (for Sonatype)

If you're missing any access, contact your team lead before release day.

### Understanding Viaduct Versioning

Before starting the release process, understand how Viaduct manages versions:

#### The VERSION File

In the root directory of the Viaduct repository is a file called `VERSION`:

- **On main branch:** Always contains a `-SNAPSHOT` version (e.g., `0.7.0-SNAPSHOT`)
- **On release branches:** Contains the actual release version (e.g., `0.7.0`)

**Example flow:**
1. Main branch has: `0.7.0-SNAPSHOT`
2. During release week:
   - Main is bumped to: `0.8.0-SNAPSHOT`
   - Release branch `release/v0.7.0` is created with VERSION: `0.7.0`

#### Demo App Versions

Demo applications have `gradle.properties` files with a `viaductVersion` property. This property **must always match** the root `VERSION` file.

**Syncing versions:**
```bash
./gradlew syncDemoAppVersions
```
This Gradle task updates all demo app `gradle.properties` files to match the `VERSION` file.

#### Repository Context

Different steps in the release process happen in different places:

| Step | Location | Example |
|------|----------|---------|
| Version bump PRs | **Your personal fork** | Fork → PR to main |
| Release branch creation | **Public `airbnb/viaduct` repo** | Direct push to public repo |
| Inbound PRs | Via Treehouse (Copybara) | See "Inbound Pull Request Process" |

**Important:** The public `airbnb/viaduct` repository is a mirror of Treehouse. Most work should happen in forks, but release branches are an exception where we work directly in the public repo.

### Publishing a new release

Viaduct follows a weekly release cadence.

1. During Monday's Viaduct team meeting, we will pick the release manager for the week.
2. Prior to the Wednesday Viaduct team meeting, the release manager creates a branch in Github called
   `candidate/v0.(X+1).0` where `X` is the version being released. The release manager opens a pull request against the main branch, bumping `VERSION` from `0.X.0-SNAPSHOT` to `0.(X+1).0-SNAPSHOT` and updates all the demo app `gradle.properties` files to match.
3. Once this PR is approved and merged, the release manager creates a branch off the SHA just before this version bump. This is the week's release candidate branch. This branch is called `release/vX.Y.Z`
4. The release manager triggers comprehensive testing across all supported environments by running:

   ```shell
   gh workflow run ".github/workflows/trigger-all-builds.yml" \
   --ref release/vX.Y.Z \
   -f reason="Testing release candidate v0.X.0"
   ```

   This will trigger builds on all supported combinations:
   - OS: ubuntu-latest, macos-latest, macos-15-intel
   - Java: 11, 17, 21

   Monitor the triggered builds and verify all 9 combinations pass successfully before the Wednesday meeting.

5. **[Optional but Recommended] Validate using Maven Local Cache:**

   This step lets you test the release locally before publishing to ensure everything builds correctly.

   a. **Clean your local Maven cache:**
   ```bash
   # Remove existing Viaduct artifacts to ensure fresh build
   rm -rf ~/.m2/repository/com/airbnb/viaduct
   ```

   b. **Publish to Maven local:**
   ```bash
   cd ~/repos/viaduct-public    # Or wherever your public viaduct clone is
   git checkout release/v0.X.0  # Your release branch
   ./gradlew publishToMavenLocal
   ```

   This puts the build artifacts in `~/.m2/repository/`

   c. **Configure demo apps to use Maven local:**
   ```bash
   # Update all demo app settings.gradle.kts to check mavenLocal() first
   for i in demoapps/*/settings.gradle.kts; do
     # Backup first
     cp "$i" "$i.backup"

     # Add mavenLocal() to repositories
     sed -i '' 's/^\([ ]*\)repositories {/\1repositories { mavenLocal()/g' "$i"
   done
   ```

   Note: This sed command adds `mavenLocal()` after `repositories {`. The formatting won't be perfect (no newline), but it works.

   d. **Test each demo app:**
   ```bash
   # Test starwars
   cd demoapps/starwars
   ./gradlew clean test --scan

   # Test cli-starter
   cd ../cli-starter
   ./gradlew clean test --scan

   # Test ktor-starter
   cd ../ktor-starter
   ./gradlew clean test --scan

   # Test jetty-starter
   cd ../jetty-starter
   ./gradlew clean test --scan
   ```

   The `--scan` flag generates a build scan URL. Open it and verify dependencies are being pulled from `~/.m2/repository` (local) and not from remote repositories.

   e. **Restore demo app settings:**
   ```bash
   # Restore backups (don't commit the mavenLocal changes)
   for i in demoapps/*/settings.gradle.kts.backup; do
     mv "$i" "${i%.backup}"
   done
   ```

   **Why this step is valuable:**
   - Catches build issues before artifacts are published publicly
   - Validates that demo apps work with the new version
   - Faster than waiting for Maven Central sync
   - Can be done while GitHub Actions are running

   **Troubleshooting:**
   - **Build fails with signing error:** This should not happen after the signing fix. If it does, ensure you're using the updated tools/build.gradle.kts
   - **Dependencies not from local:** Check build scan, ensure `mavenLocal()` was added correctly
   - **Tests fail:** Investigate if it's a real issue or environmental problem

6. **Generate and Share Changelog (Before Wednesday Meeting):**

   Prior to the Wednesday team meeting, the release manager should generate and review the changelog.

   a. **Generate changelog:**
   ```bash
   cd ~/repos/viaduct-public    # Or wherever your public viaduct clone is
   git checkout release/v0.X.0  # Your release branch

   # Generate changelog from previous release to current HEAD
   ./.github/scripts/generate_changelog.py origin/release/v0.(X-1).0 HEAD
   ```

   **Example:** If releasing `0.7.0` and previous release was `0.6.0`:
   ```bash
   git checkout release/v0.7.0
   ./.github/scripts/generate_changelog.py origin/release/v0.6.0 HEAD
   ```

   b. **Clean up the output:**

   The generated changelog may include:
   - Bookkeeping commits at the start (e.g., "Bump version to X.Y.Z-SNAPSHOT")
   - Bookkeeping commits at the end (e.g., "Set version to X.Y.Z")
   - Unclear or overly technical commit messages

   Edit the changelog to:
   - Remove version bump commits
   - Clarify cryptic commit messages
   - Group related changes if helpful
   - Ensure it's understandable to users, not just developers

   c. **Save the changelog:**
   ```bash
   # Save to a file for later use
   ./.github/scripts/generate_changelog.py origin/release/v0.6.0 HEAD > /tmp/release-v0.7.0-changelog.md

   # Edit with your preferred editor
   code /tmp/release-v0.7.0-changelog.md  # or vim, nano, etc.
   ```

   d. **Share with team:**
   - Post the edited changelog in the Viaduct OSS Slack channel
   - Ask for feedback: "Proposed changelog for v0.7.0 release. Please review before Wednesday meeting."
   - Note any concerns or suggested edits

   e. **Keep the file handy:**
   You'll paste this into the GitHub release page in Step 14.

   f. **At the Wednesday OSS team meeting,** present the changelog and lead a discussion to reach approval on the week's release.
7. If the team agrees on releasing the proposed change set, the release manager proceeds with the release.
   - If necessary based on team discussion, the release manager may wait for an in-flight change to land and will cherry-pick the change into the release branch once it is merged into the main branch.
8. In the release candidate branch, the release manager bumps the `VERSION` file to the desired release version and updates the demo app gradle.properties files to match the version file.
9. The release manager manually invokes a Github Action that uses `HEAD` of the release candidate branch via

```
gh workflow run ".github/workflows/release.yml" \
--ref main \
-f release_version=0.7.0 \
-f previous_release_version=0.6.0 \
-f publish_snapshot=false
```

Update parameters as needed. This workflow will:
  - Package release artifacts using Gradle.
  - Publish plugin artifacts to the Gradle Plugin Portal.
  - Stage a deployment to Sonatype.
  - Pushes a `vX.Y.Z` tag to Github.
  - Create a draft Github release with changelog.

10. Verify that deployments to Sonatype are successfully validated. Log in as `viaductbot` (credentials in shared 1Password vault).
11. Release manager reviews draft Github release and artifacts published to Sonatype and Gradle. This includes reviewing the changelog.
    - If the release manager rejects the release, start over with an incremented patch version. Once artifacts are published, they may not be changed.
12. If the release manager is satisfied, manually publish the Sonatype deployments. Make sure to publish all three deployments. Publishing takes 5-10 minutes.
13. Release manager must verify standalone demo apps against the newly published versions of artifacts in Maven Central and Gradle Plugin Portal.
      - Demo apps are published only on **release branches** (format: `release/v[major].[minor].[patch]`)
      - Each demo app must build successfully and have a `viaductVersion` that matches the release version
      - **Publishing with GitHub Actions (Recommended)**:
        1. Ensure you're on a release branch (e.g., `release/v0.7.0`)
        2. Go to the Actions tab in GitHub at `airbnb/viaduct`
        3. Select "Publish Demo Apps" workflow
        4. Click "Run workflow" and select the release branch

        The workflow will:
        - Validate each demo app in parallel (version check + build)
        - Update each demo app's `gradle.properties` with the release version
        - Use Copybara to sync each demo app to its external repository

      - **Local validation before publishing**:
        ```shell
        # Validate a specific demo app
        python3 ./.github/scripts/validate_demoapp.py starwars
        ```

      - **Manual copybara execution (Advanced)**:
        ```shell
        # Ensure SSH keys are configured
        ssh -T git@github.com

        # Run copybara manually from projects/viaduct/oss
        tools/copybara/run migrate \
          .github/copybara/copy.bara.sky \
          airbnb-viaduct-to-starwars \
          --git-destination-url=git@github.com:viaduct-graphql/starwars.git \
          --git-committer-email=viabot@ductworks.io \
          --git-committer-name=ViaBot \
          --force
        ```

      - **Demo apps published**:
        - `starwars` → `viaduct-graphql/starwars`
        - `cli-starter` → `viaduct-graphql/cli-starter`
        - `ktor-starter` → `viaduct-graphql/ktor-starter`

      - **Troubleshooting**:
        - **Version mismatch**: Update `viaductVersion` in demo app's `gradle.properties` to match branch version
        - **Build failure**: Test locally with `cd demoapps/starwars && ./gradlew clean build`
        - **Not on release branch**: Create and switch to a release branch (format: `release/v[major].[minor].[patch]`)
        - **Authentication errors**: Verify GitHub secrets are configured (`VIADUCT_GRAPHQL_GITHUB_ACCESS_TOKEN`)
        - **SSH authentication**: Ensure keys are in GitHub and agent is running (`ssh-add ~/.ssh/id_rsa`)

14. **⚠️ IMPORTANT: Publish the GitHub Release**

    **This step has been skipped in previous releases but is critical for user visibility.**

    Once artifacts are published to Maven Central and Gradle Plugin Portal, and demo apps are verified, make the release official on GitHub.

    a. **Navigate to releases page:**
    ```bash
    # Open in browser
    open https://github.com/airbnb/viaduct/releases
    # Or use gh CLI
    gh release list --repo airbnb/viaduct
    ```

    b. **Find the draft release:**
    - You should see a draft release for `v0.X.0` created by the GitHub Action in Step 9
    - Click "Edit" on this draft

    c. **Add the changelog:**
    - If you generated a changelog in step 6, copy it
    - Paste it into the description field of the release
    - Preview the markdown to check formatting

    d. **Publish the release:**
    - Review one final time:
      - [ ] Version tag is correct (e.g., `v0.7.0`)
      - [ ] Target is the correct release branch
      - [ ] Changelog is complete and well-formatted
    - ✅ **Check the "Set as the latest release" box** (very important!)
    - Click **"Publish release"**

    e. **Verify publication:**
    - Visit https://github.com/airbnb/viaduct/releases
    - Confirm your release shows as "Latest"
    - Share the release link in Slack: `https://github.com/airbnb/viaduct/releases/tag/v0.X.0`

    **Why this matters:**
    - GitHub releases page is often the first place users check for new versions
    - "Latest" badge helps users quickly find the current version
    - Changelog provides important migration information
    - Links from external sites often point to the releases page

### Adding a New Demo App

To add a new demo app to the publishing workflow:

1. **Add the demo app to the Copybara config** (`.github/copybara/copy.bara.sky`):
   ```python
   DEMO_APPS = [
       "starwars",
       "cli-starter",
       "ktor-starter",
       "your-new-app",  # Add here
   ]
   ```

2. **Add the demo app to the workflow** (`.github/workflows/publish-demoapps.yml`):

   In the `validate` job matrix:
   ```yaml
   matrix:
     demoapp: [starwars, cli-starter, ktor-starter, your-new-app]
   ```

   In the `publish` job matrix:
   ```yaml
   matrix:
     include:
       - name: starwars
         repo: viaduct-graphql/starwars
       - name: your-new-app
         repo: viaduct-graphql/your-new-app
   ```

3. **Ensure the demo app has proper structure**:
   - Located in `demoapps/your-new-app/`
   - Has a `gradle.properties` with `viaductVersion` property
   - Builds independently with `./gradlew build`

4. **Create the destination repository** in the `viaduct-graphql` organization on GitHub

### Inbound Pull Request Process

Viaduct's source of truth is in Airbnb's monorepo, Treehouse. The Github repository
at `airbnb/viaduct` is a mirror of the Treehouse source subtree. When a pull request is
opened against the Github repository and approved, the changes must first be applied to
Treehouse:

1. External contributor opens a pull request against `airbnb/viaduct`.
2. Viaduct maintainer reviews and approves the pull request in Github.
3. Viaduct maintainer applies the changes to Treehouse using Copybara.
    - **Option 1: Using pull-me (recommended, requires gh CLI)**:
      - **One-time setup**:
        - Authenticate with GitHub CLI: `gh auth login` (for github.com) and `gh auth login --hostname git.musta.ch` (for internal GHE)
        - Configure your GitHub username: `echo "githubUsername: YOUR_GITHUB_USERNAME" >> ~/.yak/config.yml`
      - Run `yak script projects/viaduct/oss:pull-me` to automatically pull your latest PR, or
      - Run `yak script projects/viaduct/oss:pull-me <PR_NUMBER>` to pull a specific PR
      - Use `--override` flag to re-run with force push: `yak script projects/viaduct/oss:pull-me --override <PR_NUMBER>`
    - **Option 2: Using manual-inbound (uses tokens)**:
      - **One-time setup**: Configure your GitHub username:
        ```shell
        echo "githubUsername: YOUR_GITHUB_USERNAME" >> ~/.yak/config.yml
        ```
      - Run `yak script projects/viaduct/oss:manual-inbound` to automatically pull your latest PR, or
      - Run `yak script projects/viaduct/oss:manual-inbound <PR_NUMBER>` to pull a specific PR
      - The script will automatically set up GitHub tokens if needed on first run
    - Stamp and merge the change in Treehouse.
4. Treehouse CI will automatically update the Github repo and close the inbound PR.
