// The update toast. Presentational only: it shows when `show` is true and emits `reload` / `dismiss`;
// all the detection and dismissal logic lives in useUpdateCheck.js. The message names the API (the
// loaded doc's info.title, default "API") but not the specific change — Reload re-fetches both the assets
// and the spec. role="status" announces it politely without stealing focus, and its fixed position means
// it never shifts the page, so it can appear without interrupting whatever the user is doing.
export default {
  name: 'UpdateToast',
  props: {
    show: { type: Boolean, default: false },
    title: { type: String, default: 'API' }
  },
  emits: ['reload', 'dismiss'],
  template: `
    <div v-if="show" class="update-toast" role="status">
      <span class="update-toast-msg">{{ title }} was updated.</span>
      <button type="button" class="update-toast-reload" @click="$emit('reload')">Reload</button>
      <button type="button" class="update-toast-dismiss" aria-label="Dismiss" @click="$emit('dismiss')">✕</button>
    </div>
  `
}
