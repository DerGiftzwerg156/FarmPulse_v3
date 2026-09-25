# Frontend (Angular) – decisions

| Topic | Decision | Reason |
| --- | --- | --- |
| Framework | Angular 22, standalone components, strict TypeScript, Angular Router | Current stable at implementation time (checked 2026-09-25). |
| Node | Angular CLI 22 requires Node ≥ 22.22.3 / 24.15; the project uses Node 24 LTS. | CLI hard requirement. |
| Styling | Tailwind CSS 4 with the binding `tailwind.config.js` (tokens extracted from `docs/design-reference/Dashboard.html`), loaded through `@config` in `src/styles.css`; custom `fp-*` utilities copied 1:1 from the reference. | Keeps the concept's config file while using the current Tailwind major. |
| Fonts | Google Fonts `Chakra Petch` (500/600/700, display) and `Barlow` (400/500/600, body). | Same source as the design reference. |
| i18n | Own `TranslationService` + `t` pipe with `src/app/core/i18n/de.json`; no hard-coded German strings in templates. | `@angular/localize` needs one build per locale and extraction tooling; V1 is German-only but must stay extensible – a JSON dictionary per locale is enough and switchable at runtime. |
| Visual acceptance | Internal route `/dev/style-guide` (only in the development build via `environment.styleGuide`) instead of Storybook. | No extra toolchain; the page renders every shared component with sample data and is also used by the screenshot generator. |
| Unit tests | Vitest via `@angular/build:unit-test` (Angular 22 default, jsdom). `npm test -- --watch=false`. | Angular's current default runner; Karma is deprecated. |
| Lint | angular-eslint (`npm run lint`). | Standard Angular lint setup. |
| Dev proxy | `proxy.conf.json` forwards `/api` to `http://localhost:8080`. | Frontend uses relative `/api` URLs in every environment. |
