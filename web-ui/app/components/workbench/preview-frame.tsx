/** Opaque-origin sandbox: preview scripts may run, but cannot access the app. */
export function PreviewFrame({ title, document }: { title: string; document: string }) {
  return (
    <iframe
      title={title}
      sandbox="allow-scripts"
      referrerPolicy="no-referrer"
      srcDoc={document}
      className="h-full w-full border-0"
    />
  );
}
