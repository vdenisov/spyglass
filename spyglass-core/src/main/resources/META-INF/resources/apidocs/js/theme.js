// Theme preference: the user's literal choice, persisted per browser. The CSS resolves
// "system" against prefers-color-scheme, so no matchMedia listener is needed here — the OS
// theme is followed live by CSS while the choice is "system".
//
// The same localStorage key is read by a tiny inline script in index.html that applies the
// theme before first paint (avoiding a flash); keep its namespace resolution in sync with config.js.
import { storageKey } from './config.js'

export const THEME_KEY = storageKey('theme')
export const THEMES = ['light', 'system', 'dark']

export const getTheme = () => localStorage.getItem(THEME_KEY) || 'system'

export const setTheme = (theme) => {
  localStorage.setItem(THEME_KEY, theme)
  document.documentElement.dataset.theme = theme
}
