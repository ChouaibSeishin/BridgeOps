# Git and GitHub development workflow

Repository: https://github.com/ChouaibSeishin/BridgeOps. Preserve the existing local master branch. At inspection the local repository had no commits/remotes/tracked files, and GitHub's public repository contained no refs. Do not overwrite history if either side changes before publication.

## Before each commit

1. Use `git status --short` and `git diff` to identify the exact increment. Keep .env, private keys, database exports, archives, caches/build outputs and local tools out of Git. .env.example contains empty placeholders only.
2. Run the narrow relevant tests, then the documented full milestone checks when applicable. Do not commit an affected failing change without the user's approval unless the failure is corrected.
3. Stage the intended files, inspect `git diff --cached --stat`, `git diff --cached` and `git diff --cached --check`. Review screenshots visually and scan staged text for secrets using a redacting scanner. Never print actual passwords/tokens while investigating.
4. Use a Conventional Commit describing one coherent, verified increment. The first commit preserves the already-built Phase 1A foundation; subsequent identity work is separate.
5. Push only to the confirmed remote, normally after each completed increment. No force push, reset, remote replacement or fabricated commit/CI status.

`git ls-files .env` must be empty. An ignored previously tracked secret requires removal from the index and rotation; an exposed historical secret also needs a reviewed history-remediation plan before publishing. No such tracked history existed at the initial inspection. See [local credential rotation](../security/LOCAL_CREDENTIAL_ROTATION.md) for the potentially shared archive.

## Connect and publish

The user has authorized this exact public repository. Once the verified initial commit exists, and only if origin is still absent:

```powershell
git remote add origin https://github.com/ChouaibSeishin/BridgeOps.git
git push -u origin master
```

If origin already exists, inspect it rather than replacing it. Authentication uses the local Git credential manager or an authorized connector; never put a personal access token in the URL/configuration or source. If no credentials are available, authenticate locally using Git Credential Manager's normal GitHub sign-in or an official GitHub CLI `gh auth login`, then retry the same push. Do not paste tokens into chat. Verify remote refs and the actual GitHub result before reporting publication.

The GitHub plugin is available but was not confirmed connected during initial inspection. Its installation is optional if normal Git authentication works. No permission to push or create private vulnerability reports is inferred merely from the existence of a public repository.

## Repository presentation and release gates

Do not claim CI passing until GitHub Actions has actually completed successfully. Do not add a license until the user deliberately chooses one. Before a public release, configure and test a real private vulnerability-reporting channel and document its instructions. Public development source is explicitly a pre-release work in progress, not a production release or security certification.
