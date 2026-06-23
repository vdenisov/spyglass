// The Spyglass release version. The Maven property below is substituted at build time by resource
// filtering — scoped to this one file in spyglass-core/pom.xml so the vendored bundles are never
// filtered. In an unfiltered checkout (e.g. serving src directly) the placeholder stays literal; the
// footer detects the leading '@' and omits the version line rather than showing the raw placeholder.
export const VERSION = '@project.version@'
