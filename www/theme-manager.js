// ═══════════════════════════════════════════════════════════════════════════════
//  THEME MANAGER — Dark Mode & Light Mode Toggle
//  Manages theme switching, persistence, and system preference detection
// ═══════════════════════════════════════════════════════════════════════════════

class ThemeManager {
  constructor() {
    this.STORAGE_KEY = 'unifest-theme-preference';
    this.DARK_MODE_CLASS = 'dark-mode';
    this.init();
  }

  /**
   * Initialize theme on page load
   * Priority: Saved preference > System preference > Default (light)
   */
  init() {
    const savedTheme = this.getSavedTheme();
    const systemPrefersDark = this.getSystemPreference();
    const theme = savedTheme || (systemPrefersDark ? 'dark' : 'light');
    this.setTheme(theme);
  }

  /**
   * Get saved theme from localStorage
   */
  getSavedTheme() {
    try {
      return localStorage.getItem(this.STORAGE_KEY);
    } catch (e) {
      console.warn('localStorage unavailable:', e.message);
      return null;
    }
  }

  /**
   * Detect system dark mode preference
   */
  getSystemPreference() {
    if (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches) {
      return true;
    }
    return false;
  }

  /**
   * Set theme and update DOM + storage
   * @param {string} theme - 'light' or 'dark'
   */
  setTheme(theme) {
    const isDark = theme === 'dark';
    const html = document.documentElement;
    const body = document.body;

    if (isDark) {
      body.classList.add(this.DARK_MODE_CLASS);
      html.style.colorScheme = 'dark';
    } else {
      body.classList.remove(this.DARK_MODE_CLASS);
      html.style.colorScheme = 'light';
    }

    this.saveSetting(theme);
    this.dispatchThemeChangeEvent(theme);
  }

  /**
   * Save theme preference to localStorage
   */
  saveSetting(theme) {
    try {
      localStorage.setItem(this.STORAGE_KEY, theme);
    } catch (e) {
      console.warn('Could not save theme preference:', e.message);
    }
  }

  /**
   * Dispatch custom event for theme changes
   */
  dispatchThemeChangeEvent(theme) {
    window.dispatchEvent(new CustomEvent('theme-changed', { detail: { theme } }));
  }

  /**
   * Toggle between light and dark modes
   */
  toggle() {
    const current = document.body.classList.contains(this.DARK_MODE_CLASS) ? 'dark' : 'light';
    const next = current === 'dark' ? 'light' : 'dark';
    this.setTheme(next);
    return next;
  }

  /**
   * Get current theme
   */
  getCurrentTheme() {
    return document.body.classList.contains(this.DARK_MODE_CLASS) ? 'dark' : 'light';
  }
}

// Initialize globally
const themeManager = new ThemeManager();

// Listen for system theme changes
if (window.matchMedia) {
  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', (e) => {
    // Only auto-switch if user hasn't manually set a preference
    if (!localStorage.getItem(themeManager.STORAGE_KEY)) {
      themeManager.setTheme(e.matches ? 'dark' : 'light');
    }
  });
}
