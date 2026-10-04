import { useEffect, useId, useRef, useState, type ButtonHTMLAttributes, type FormEvent, type HTMLAttributes, type ReactNode } from 'react';

export type PaneLayout = 'compact' | 'medium' | 'expanded';
export type PaneRole = 'navigation' | 'primary' | 'supporting';
export interface SurfaceConstraints { width: number; height: number; textScale?: number; hasFinePointer?: boolean }
export interface EntryPresentation { entryId: string; role: PaneRole }
export interface PresentationPlan { layout: PaneLayout; panes: EntryPresentation[]; focusedEntryId: string }

/** Pure projection of Graph route and selection. Resizing never changes their identity. */
export function presentationPlan(routeEntryId: string, supportingEntryId: string | null, constraints: SurfaceConstraints): PresentationPlan {
  const usableWidth = constraints.width / Math.max(1, constraints.textScale ?? 1);
  const layout: PaneLayout = usableWidth >= 1120 && constraints.height >= 480 ? 'expanded' : usableWidth >= 720 ? 'medium' : 'compact';
  const panes: EntryPresentation[] = [];
  if (layout !== 'compact') panes.push({ entryId: 'navigation', role: 'navigation' });
  panes.push({ entryId: routeEntryId, role: 'primary' });
  if (layout === 'expanded' && supportingEntryId) panes.push({ entryId: supportingEntryId, role: 'supporting' });
  return { layout, panes, focusedEntryId: routeEntryId };
}

/** Renderer measurement hook; the returned plan is independent of navigation state. */
export function usePresentationPlan(routeEntryId: string, supportingEntryId: string | null, container: React.RefObject<HTMLElement | null>): PresentationPlan {
  const [constraints, setConstraints] = useState<SurfaceConstraints>({ width: 390, height: 800 });
  useEffect(() => {
    const element = container.current;
    if (!element) return;
    const measure = () => setConstraints({ width: element.clientWidth, height: element.clientHeight, textScale: Number.parseFloat(getComputedStyle(element).fontSize) / 16, hasFinePointer: matchMedia('(pointer: fine)').matches });
    const observer = new ResizeObserver(measure);
    observer.observe(element);
    measure();
    return () => observer.disconnect();
  }, [container]);
  return presentationPlan(routeEntryId, supportingEntryId, constraints);
}

export type SurfaceActionState = 'ready' | 'busy' | 'unavailable';

export interface SurfaceActionProps extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'onClick'> {
  state?: SurfaceActionState;
  appearance?: string;
  onAction?: () => void | Promise<void>;
  children: ReactNode;
}

// The adapter owns activation and busy state. Appearance classes draw only.
export function SurfaceAction({ state = 'ready', appearance = 'plain', onAction, children, className = '', disabled, ...rest }: SurfaceActionProps) {
  const [pending, setPending] = useState(false);
  const blocked = disabled || pending || state !== 'ready';
  const activate = () => {
    if (blocked || !onAction) return;
    const result = onAction();
    if (result && typeof (result as Promise<void>).then === 'function') {
      setPending(true);
      void Promise.resolve(result).finally(() => setPending(false));
    }
  };
  return <button {...rest} type={rest.type ?? 'button'} className={`surface-action surface-action--${appearance} ${className}`} disabled={blocked} aria-busy={pending || state === 'busy'} onClick={activate}>{children}</button>;
}

export function SurfacePanel({ children, className = '', ...rest }: HTMLAttributes<HTMLElement> & { children: ReactNode }) {
  return <section {...rest} className={`surface-panel ${className}`}>{children}</section>;
}

export interface SurfaceChoice<T extends string> { id: T; label: string }
export function SurfaceTabs<T extends string>({ label, value, choices, onChange }: { label: string; value: T; choices: SurfaceChoice<T>[]; onChange: (value: T) => void }) {
  return <div className="surface-tabs" role="tablist" aria-label={label}>{choices.map((choice, index) => <button key={choice.id} type="button" role="tab" aria-selected={choice.id === value} tabIndex={choice.id === value ? 0 : -1} className={choice.id === value ? 'is-selected' : ''} onClick={() => onChange(choice.id)} onKeyDown={event => {
    const next = event.key === 'ArrowRight' ? (index + 1) % choices.length : event.key === 'ArrowLeft' ? (index - 1 + choices.length) % choices.length : event.key === 'Home' ? 0 : event.key === 'End' ? choices.length - 1 : -1;
    if (next < 0) return;
    event.preventDefault();
    onChange(choices[next].id);
    (event.currentTarget.parentElement?.querySelectorAll<HTMLButtonElement>('[role="tab"]')[next])?.focus();
  }}>{choice.label}</button>)}</div>;
}

export interface SurfaceDraft {
  value: string;
  revision: number;
  savedRevision: number;
  set: (value: string) => void;
  acknowledge: (revision: number) => void;
}

export function useSurfaceDraft(initial: string): SurfaceDraft {
  const [state, setState] = useState({ value: initial, revision: 0, savedRevision: 0 });
  return {
    ...state,
    set: value => setState(previous => ({ ...previous, value, revision: previous.revision + 1 })),
    acknowledge: revision => setState(previous => ({ ...previous, savedRevision: Math.max(previous.savedRevision, Math.min(revision, previous.revision)) })),
  };
}

export function SurfaceField({ label, value, onValue, multiline = false, hint, id, ...rest }: {
  label: string; value: string; onValue: (value: string) => void; multiline?: boolean; hint?: string; id?: string;
} & Omit<React.InputHTMLAttributes<HTMLInputElement>, 'value' | 'onChange'>) {
  const generatedId = useId();
  const fieldId = id ?? generatedId;
  const hintId = `${fieldId}-hint`;
  const labelId = `${fieldId}-label`;
  return <label className="surface-field" htmlFor={fieldId}><span id={labelId}>{label}</span>{multiline
    ? <textarea {...rest as React.TextareaHTMLAttributes<HTMLTextAreaElement>} id={fieldId} aria-labelledby={labelId} value={value} onChange={event => onValue(event.target.value)} aria-describedby={hint ? hintId : undefined} />
    : <input {...rest} id={fieldId} aria-labelledby={labelId} value={value} onChange={event => onValue(event.target.value)} aria-describedby={hint ? hintId : undefined} />}
    {hint && <small id={hintId}>{hint}</small>}</label>;
}

export function SurfaceDialog({ title, open, onClose, children }: { title: string; open: boolean; onClose: () => void; children: ReactNode }) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    if (open && !dialog.open) dialog.showModal();
    if (!open && dialog.open) dialog.close();
  }, [open]);
  const titleId = useId();
  return <dialog ref={ref} className="surface-dialog" aria-labelledby={titleId} onClose={onClose} onCancel={onClose}><div className="surface-dialog__head"><h2 id={titleId}>{title}</h2><SurfaceAction aria-label="Close" onAction={onClose}>×</SurfaceAction></div>{children}</dialog>;
}

export function SurfaceForm({ onSubmit, children, className = '' }: { onSubmit: () => void | Promise<void>; children: ReactNode; className?: string }) {
  const submit = (event: FormEvent) => { event.preventDefault(); void onSubmit(); };
  return <form className={className} onSubmit={submit}>{children}</form>;
}
