/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Account number used when the URL has no `?account=`; set only for development builds. */
  readonly VITE_DEFAULT_ACCOUNT_NUMBER?: string
}
