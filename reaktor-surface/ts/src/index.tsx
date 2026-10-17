import type { ButtonHTMLAttributes, FunctionComponent, HTMLAttributes, InputHTMLAttributes, ReactElement, ReactNode, RefObject } from 'react';
import { SurfaceWeb } from 'reaktor-reaktor-surface';

const surface = SurfaceWeb.getInstance();

export type PaneLayout = 'compact' | 'medium' | 'expanded';
export type PaneRole = 'navigation' | 'primary' | 'supporting';
export interface SurfaceConstraints { width: number; height: number; textScale?: number; hasFinePointer?: boolean }
export interface EntryPresentation { entryId: string; role: PaneRole }
export interface PresentationPlan { layout: PaneLayout; panes: EntryPresentation[]; focusedEntryId: string }

export const presentationPlan = surface.plan as (route: string, supporting: string | null, constraints: SurfaceConstraints) => PresentationPlan;
export const usePresentationPlan = surface.usePresentation as (route: string, supporting: string | null, container: RefObject<HTMLElement | null>) => PresentationPlan;

export type SurfaceActionState = 'ready' | 'busy' | 'unavailable';
export interface SurfaceActionProps extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'onClick'> {
  state?: SurfaceActionState;
  appearance?: string;
  onAction?: () => void | Promise<void>;
  children: ReactNode;
}
export const SurfaceAction = surface.Action as FunctionComponent<SurfaceActionProps>;
export const SurfacePanel = surface.Panel as FunctionComponent<HTMLAttributes<HTMLElement> & { children: ReactNode }>;

export interface SurfaceChoice<T extends string> { id: T; label: string; disabled?: boolean; panelId?: string }
export const SurfaceTabs = surface.Tabs as <T extends string>(props: {
  label: string; value: T; choices: SurfaceChoice<T>[]; onChange: (value: T) => void; dir?: 'ltr' | 'rtl';
}) => ReactElement;

export interface SurfaceDraft {
  value: string;
  revision: number;
  savedRevision: number;
  set: (value: string) => void;
  acknowledge: (revision: number) => void;
}
export const useSurfaceDraft = surface.useDraft as (initial: string) => SurfaceDraft;
export const SurfaceField = surface.Field as FunctionComponent<{
  label: string; value: string; onValue: (value: string) => void; multiline?: boolean; hint?: string; id?: string;
} & Omit<InputHTMLAttributes<HTMLInputElement>, 'value' | 'onChange'>>;
export const SurfaceDialog = surface.Dialog as FunctionComponent<{
  title: string; open: boolean; onClose: () => void; children: ReactNode;
}>;
export const SurfaceForm = surface.Form as FunctionComponent<{
  onSubmit: () => void | Promise<void>; children: ReactNode; className?: string;
}>;
