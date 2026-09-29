// tailwind.config.js — Farb-/Font-Tokens aus docs/design-reference/Dashboard.html (verbindlich, siehe AP-7.1).
// Tailwind v4 lädt diese Datei über die @config-Direktive in src/styles.css.
/** @type {import('tailwindcss').Config} */
module.exports = {
  content: ['./src/**/*.{html,ts}'],
  theme: {
    extend: {
      colors: {
        bg: '#0B0F0D', // Haupt-Hintergrund
        surface: '#141A17', // Karten/Panels
        border: '#222E28', // Rahmen (Opacity-Varianten 30/40/50/60 nach Bedarf)
        accent: '#38B000', // Primärakzent (Grün) — CTAs, aktive States, Erfolg
        warn: '#FF9F1C', // Sekundärakzent (Orange) — Warnungen, Preisänderungen
        text: '#E2ECE9', // Primärtext
        muted: '#7C9088', // Sekundärtext/Platzhalter
        danger: '#dc2626', // Fehler/negative Werte
        // Hof-Tablet: Symbolfarben der App-Gruppen (Kommunikation, Geld, Hof, System)
        'app-com': '#8CC8EA',
        'app-money': '#FFB547',
        'app-farm': '#6FE03A',
        'app-sys': '#B9C8C2',
        tile: '#151D19', // App-Kachel
        'tile-active': '#1F2A1A', // aktive App in der Schnellleiste
        chrome: '#0E1311', // Statusleiste, Schnellleiste
      },
      fontFamily: {
        body: ['Barlow', 'ui-sans-serif', 'system-ui', 'sans-serif'],
        display: ['"Chakra Petch"', 'ui-sans-serif', 'system-ui', 'sans-serif'],
        mono: ['ui-monospace', 'SFMono-Regular', 'Menlo', 'Monaco', 'Consolas', 'monospace'],
      },
    },
  },
};
