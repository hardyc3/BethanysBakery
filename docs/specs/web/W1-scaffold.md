# W1 — Customer Web Scaffold, Design System, and API Client

- Status: ready
- Depends on: S1
- Contract: `docs/api/openapi.yaml` → `getPublicConfig`
- Next: W2

## Goal

Create the customer React PWA foundation: build tooling, routing, design tokens, responsive layout, typed API client, error handling, and test harness.

## Non-goals

- Authentication.
- Menu/order features.
- PWA service worker (W8).
- Production nginx/Docker config (W8/S7).

## Deliverables

- `web/` Vite project:
  - React 19
  - TypeScript strict
  - React Router
  - TanStack Query
  - Zod
  - Tailwind CSS
  - ESLint + Prettier
  - Vitest + React Testing Library + MSW
  - Playwright config
  - `eslint-plugin-jsx-a11y`
- `VITE_API_BASE_URL` default empty (same origin `/api/v1`).
- Generated API types from `docs/api/openapi.yaml` via `openapi-typescript`; commit generated types.
- Scripts: `dev`, `build`, `preview`, `test`, `test:watch`, `e2e`, `lint`, `typecheck`, `generate:api`.
- Design tokens:
  - warm bakery palette, accessible contrast
  - spacing, radii, shadows, typography scale
  - mobile-first at 360 px and up
  - large tap targets (min 44 px)
  - reduced-motion support
- Layout:
  - header with bakery name and nav placeholders
  - main content container
  - footer with support email, pickup address, maps link
  - skip link
  - error boundary
  - 404 route
- Components:
  - Button, LinkButton, Input, Select, Textarea, Checkbox, RadioGroup
  - Card, Badge, Alert, Spinner, Skeleton
  - Dialog/Sheet
  - Price display helper (cents → `$4.00`)
  - EmptyState, ErrorState
- API client:
  - `fetch` wrapper with `credentials: 'include'`
  - base URL resolution
  - JSON parsing
  - `AbortSignal`
  - Problem Details parsing into `ApiError { status, code, title, detail, fieldErrors, requestId }`
  - network/timeout errors mapped to a friendly error
  - CSRF token holder (cookie `bb_csrf`, header `X-CSRF-Token`) prepared for W2
  - no retries for mutations
- Query client defaults:
  - one retry for GET network errors
  - no retry for 4xx
  - refetch on window focus for availability
- Config query:
  - fetch `/api/v1/config`
  - display bakery name, support email, pickup address, maps link
  - loading/error states
- Accessibility:
  - semantic landmarks
  - focus-visible styles
  - form labels and error associations
  - color contrast checks
- `web/README.md`: scripts, env, structure, conventions.

## Suggested structure

```text
web/src/
  app/            router, providers, layout
  api/            client, generated types, queries
  components/ui/  primitives
  components/     shared composites
  features/       feature folders (empty placeholders)
  lib/            money, dates, errors
  styles/
  test/           MSW setup, fixtures
```

## Ordered tasks

1. Scaffold Vite React TS and configure strict settings.
2. Add Tailwind, tokens, base styles, fonts, reduced motion.
3. Add router, providers, layout, error boundary, 404.
4. Build UI primitives and document usage.
5. Generate API types and implement the fetch client.
6. Implement config query and footer/header data.
7. Add Money/Date utilities with America/Denver display helpers.
8. Add Vitest/RTL/MSW and Playwright config.
9. Add ESLint/Prettier/a11y rules and scripts.
10. Write README and handoff notes.

## Acceptance criteria

- Given `pnpm install && pnpm dev`, when opening the app, then the layout renders with bakery name from the API.
- Given the API is down, when the app loads, then a friendly error state appears with a retry action.
- Given a Problem Details response, when a request fails, then `ApiError.code` and field errors are available to UI code.
- Given the viewport is 360 px, then no horizontal scroll and tap targets are at least 44 px.
- Given keyboard-only navigation, then all interactive elements are reachable and focus is visible.
- Given `pnpm build`, then the production build succeeds with no TypeScript errors.
- Given `pnpm test`, then unit/component tests pass.
- Given `pnpm lint`, then no errors.
- Given a 404 route, then a helpful not-found page renders.

## Test plan

- Money formatting edge cases (0, 1, 100, 999999).
- Date formatting in America/Denver across DST.
- ApiError parsing for problem+json and malformed bodies.
- Config query success/error/loading.
- Layout snapshot/behavior at mobile and desktop.
- Keyboard navigation smoke.
- Playwright smoke: home loads, 404 works.

## Security checklist

- [ ] No secrets in web env.
- [ ] No `dangerouslySetInnerHTML`.
- [ ] External links use `rel="noopener noreferrer"`.
- [ ] API errors do not render raw HTML.
- [ ] CSP-compatible code (no inline event handlers).
- [ ] No analytics/tracking added.

## Quality gate

This phase is not complete until `/phase-gate W1` runs and blocking findings are fixed or explicitly deferred in this spec. The gate runs, in order:

1. `spec-compliance` — this spec's deliverables, API operations, migrations, acceptance criteria, test plan, security checklist, and definition of done are met; shortcuts flagged.
2. `lint` — applicable linters and secret scan on the changed scope.
3. `refactor` — readability and maintainability only, no behavior change.
4. `test-quality` — meaningful tests, edge cases, and coverage of critical invariants.
5. `security` — secure coding and deployment security audit.
6. `gap-analysis` — gaps fixed, routed to a new spec, or written into the owning future spec.

Quality-gate results belong in the phase PR description.

## Definition of done

The app builds and runs, the config endpoint drives the shell, primitives are reusable, tests pass, and W2 can add auth without restructuring.

## Handoff notes

- W2 adds auth providers and protected routes.
- W8 adds service worker, manifest, production nginx, and e2e expansion.
- Keep generated API types in sync with `docs/api/openapi.yaml`.
