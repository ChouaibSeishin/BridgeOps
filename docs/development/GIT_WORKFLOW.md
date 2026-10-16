# Git and GitHub development workflow

Repository: https://github.com/ChouaibSeishin/BridgeOps, established origin/master. Preserve the existing master branch and published history. Phase 2 inspection on 2026-10-16 found a clean checkout and matching local/remote head 11aec6e9e2e9d2e0ea0aaedde2e286de1191e538. Fetch and inspect new remote commits before each continuation; build on them without reset or force push. The empty-repository observation belonged to historical foundation setup, not the current state.

## Before each commit

1. Use `git status --short` and `git diff` to identify the exact increment. Keep .env, private keys, database exports, archives, caches/build outputs and local tools out of Git. .env.example contains empty placeholders only.
2. Run the narrow relevant tests, then the documented full milestone checks when applicable. Do not commit an affected failing change without the user's approval unless the failure is corrected.
3. Stage the intended files, inspect `git diff --cached --stat`, `git diff --cached` and `git diff --cached --check`. Review screenshots visually and scan staged text for secrets using a redacting scanner. Never print actual passwords/tokens while investigating.
4. Use a Conventional Commit describing one coherent, verified increment. Scan staged changes and committed history. Preserve existing work and separate unrelated changes.
5. Push only to the confirmed remote, normally after each completed increment. No force push, reset, remote replacement or fabricated commit/CI status.

`git ls-files .env` must be empty. An ignored previously tracked secret requires removal from the index and rotation; an exposed historical secret also needs a reviewed history-remediation plan before publishing. No such tracked history existed at the initial inspection. See [local credential rotation](../security/LOCAL_CREDENTIAL_ROTATION.md) for the potentially shared archive.

## Connect and publish

The user has authorized normal pushes to this exact public repository. Inspect branch, worktree, origin URL and remote head first:

```powershell
git status --short --branch
git remote -v
git fetch origin master
git log --oneline HEAD..origin/master
git push origin master
git rev-parse HEAD
git ls-remote origin refs/heads/master
```

If origin already exists, inspect it rather than replacing it. Authentication uses the local Git credential manager or an authorized connector; never put a personal access token in the URL/configuration or source. If no credentials are available, authenticate locally using Git Credential Manager's normal GitHub sign-in or an official GitHub CLI `gh auth login`, then retry the same push. Do not paste tokens into chat. Verify remote refs and the actual GitHub result before reporting publication.

Normal Git authentication and hosted Actions verification were exercised during Phase 1. A connector may read CI logs; installation is optional when Git authentication works. Push authorization comes from the user's development instructions, not merely from public visibility. Private vulnerability reports still need their own authorized reporting channel.

## Repository presentation and release gates

Do not claim CI passing until GitHub Actions has actually completed successfully. Do not add a license until the user deliberately chooses one. Before a public release, configure and test a real private vulnerability-reporting channel and document its instructions. Public development source is explicitly a pre-release work in progress, not a production release or security certification.

## Redacting scanner commands

This session used official Gitleaks 8.30.1, downloaded to ignored .tools and verified against the release SHA-256 manifest. With that version available on PATH, run:

```powershell
gitleaks git --staged --redact --no-banner
gitleaks git --redact --no-banner
```

The first command checks the staged snapshot; the second checks committed history. Neither proves every possible secret is absent. Do not add broad scanner exemptions to make a real credential pass.
