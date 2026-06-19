import { ref } from 'vue'
import { THEMES, getTheme, setTheme } from '../theme.js'

// Three-way Light / System / Dark segmented control. "System" follows the OS via CSS;
// the choice persists per browser (see theme.js).
const OPTIONS = {
  light: { icon: '☀', label: 'Light', desc: 'Use the light theme' },
  system: { icon: '🖥', label: 'System', desc: "Follow your operating system's theme" },
  dark: { icon: '🌙', label: 'Dark', desc: 'Use the dark theme' }
}

export default {
  name: 'ThemeToggle',
  setup() {
    const current = ref(getTheme())
    const choose = (theme) => { setTheme(theme); current.value = theme }
    return { themes: THEMES, options: OPTIONS, current, choose }
  },
  template: `
    <div class="theme-toggle" role="group" aria-label="Color theme">
      <button v-for="t in themes" :key="t" type="button"
        :class="{ active: current === t }" :aria-pressed="current === t"
        :aria-label="'Theme: ' + options[t].label" v-tip="options[t].desc" @click="choose(t)">
        <span aria-hidden="true">{{ options[t].icon }}</span>{{ options[t].label }}
      </button>
    </div>
  `
}
