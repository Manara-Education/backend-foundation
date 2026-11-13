# Design source map

**Source status:** the Figma Make export (`~/Downloads/Manara`) is unreadable in this environment
(blocker X1), and the Figma API token has expired (X2). File names, line references and visual
details below come from the gap report of 2026-09-25, **not** from inspecting the export. Every row
marked "report" must be re-checked against the export before claiming visual fidelity.

| Export file (per report) | Target in frontend-foundation | Reuse | Source |
|---|---|---|---|
| `dashboard/settings/SettingsView.tsx` | `src/features/settings/pages/settings-page.tsx`, `components/settings-nav.tsx` | `shared/navigation/paths`, route `handle.section` | report |
| `dashboard/Sidebar.tsx:14-18`, `DashboardShell.tsx` | `src/features/main/components/nav-sections.ts` | existing sidebar | report |
| `dashboard/settings/AccountSettings.tsx` | `src/features/settings/account/*` | profile api/service/mapper (`features/profile`), `features/auth/password-policy`, `shared/auth` | report |
| `dashboard/settings/AppearanceSettings.tsx` (1,176 lines) | `src/features/settings/appearance/*` (scoped CSS) | none — exact port | **blocked (X1)** |
| `dashboard/settings/BillingSettings.tsx` (Subscriptions, PaymentMethods, AddMethodModal) | `src/features/settings/billing/*` | `shared/components` | report |
| `dashboard/settings/InvoicesView.tsx` | `src/features/settings/billing/invoices/*` | `shared/components/pagination`, `sheet`/`drawer` | report |
| `payments/PaymentLogo.tsx`, `src/imports/*.svg` | provider logos, only for methods a real provider offers | — | report |
| `landing/LandingPage.tsx:571-905` (CourseCard, Courses) | `features/public-courses/components/public-course-card.tsx`, `features/landing/components/courses-section.tsx` | `OfferBadge`, `formatPlanTerm` | report |
| `PublicCourseDetails` | `features/public-courses/components/public-course-content.tsx`, `public-offer-panel.tsx` | existing CTA hook | report |
| `payments/CourseCheckout.tsx`, `PaymentStore.tsx` | `features/course/student/course-details/components/checkout-modal.tsx`, `hooks/use-checkout.ts` | existing checkout api | report; `PaymentStore` not ported |
| `dashboard/CourseDetailsView.tsx` CTA cards | existing `payment-cta-section.tsx`, `subscription-cta-section.tsx`, `subscription-status-card.tsx` | already match (report) | report |

## Layout facts from the report (to verify)

- Settings: content max width 1000 px; nav rail 248–268 px, sticky, on the right (RTL) at `lg+`;
  below `lg`, two rows of horizontal pills. Sections: الحساب والأمان · الفوترة والمدفوعات
  (الاشتراكات · طرق الدفع · الفواتير والمدفوعات) · المظهر. No "نظرة عامة".
- Identity card: 64 px gradient banner, 92 px avatar with two-letter initials, camera hover overlay,
  role pill, LTR email, "عضو منذ …" inline.
- Name editor: `EditorShell` panel with a tips aside; empty / > 70 validation; save disabled until
  changed; saving/success/failed; unsaved-changes confirm.
- Password editor: current/new/confirm with show/hide, live 4-rule checklist + strength bar,
  failed / success / session-expired states.
- Security zone: tinted "الأمان والخصوصية" card with "آخر تحديث: <date>" and an anti-phishing note.
- Photo modal ~560 px; JPG/PNG/WebP ≤ 5 MB ≥ 100×100; circular crop, drag, zoom slider, 512×512.
- Public card: category chip top-right of 16:9 cover; hover lift −4 px; decorative gradient pill.
- Public details: breadcrumb; 280 px hero with dark overlay; plan chips with running total.
- Checkout: 480 px bottom sheet titled "إتمام الشراء".
