---
description: Run all phase quality-gate agents
agent: build
---

Run the phase quality gate for $ARGUMENTS.

Use the phase ID and changed diff. Run these subagents in order:

1. `spec-compliance` — verify the phase spec, acceptance criteria, test plan, security checklist, and definition of done; flag shortcuts.
2. `lint` — run applicable linters and report violations in the changed scope.
3. `refactor` — improve readability and maintainability without behavior changes.
4. `test-quality` — verify test quality and coverage; fix weak tests.
5. `security` — audit secure coding and deployment security.
6. `gap-analysis` — find gaps and fix them, create a new spec, or update the owning future spec.

For each agent, pass the phase ID, changed files or diff, and the spec path. Do not stop at the first failure: collect all findings, fix what is safe, rerun affected checks, and re-run any agent whose fixes could regress its area.

End with a table: agent, status, findings, action taken. The phase is not complete while any blocker remains. Deferred items must be recorded in the spec or as a new spec.
