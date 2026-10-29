import { inject, Injectable, signal } from '@angular/core';
import { Application, Connectivity, Utils } from '@nativescript/core';
import { DatabaseService } from '../data/database.service';

declare const android: any;
declare const java: any;

export type ThemeMode = 'system' | 'dark' | 'light';
export type Palette = 'violet' | 'ocean' | 'forest' | 'sunset' | 'rose' | 'amber' | 'teal' | 'slate';

export interface PaletteInfo {
  value: Palette;
  label: string;
  accent: string;
  dark: { bg: string; nav: string };
  light: { bg: string; nav: string };
}

export const PALETTES: PaletteInfo[] = [
  { value: 'violet', label: 'Violet', accent: '#8052df', dark: { bg: '#100a1d', nav: '#170f25' }, light: { bg: '#f6f2fb', nav: '#ffffff' } },
  { value: 'ocean', label: 'Océan', accent: '#2f7de1', dark: { bg: '#0a1220', nav: '#0e1829' }, light: { bg: '#f1f6fc', nav: '#ffffff' } },
  { value: 'forest', label: 'Forêt', accent: '#1f9d6b', dark: { bg: '#08140f', nav: '#0c1a13' }, light: { bg: '#f1f9f4', nav: '#ffffff' } },
  { value: 'teal', label: 'Lagon', accent: '#12a39a', dark: { bg: '#081615', nav: '#0b1d1c' }, light: { bg: '#eff9f8', nav: '#ffffff' } },
  { value: 'amber', label: 'Ambre', accent: '#d98a0b', dark: { bg: '#17110a', nav: '#1d150c' }, light: { bg: '#fcf7ee', nav: '#ffffff' } },
  { value: 'sunset', label: 'Couchant', accent: '#e0694b', dark: { bg: '#1a0e0b', nav: '#20120e' }, light: { bg: '#fcf3ef', nav: '#ffffff' } },
  { value: 'rose', label: 'Rose', accent: '#e0527f', dark: { bg: '#190c13', nav: '#1f0f18' }, light: { bg: '#fcf1f5', nav: '#ffffff' } },
  { value: 'slate', label: 'Ardoise', accent: '#64748b', dark: { bg: '#0e1117', nav: '#121720' }, light: { bg: '#f3f5f8', nav: '#ffffff' } },
];
export type StorageLocation = 'internal' | 'external';

@Injectable({ providedIn: 'root' })
export class SettingsService {
  private readonly db = inject(DatabaseService);

  readonly themeMode = signal<ThemeMode>(this.db.getSetting('theme.mode', 'dark') as ThemeMode);
  readonly palette = signal<Palette>(this.db.getSetting('theme.palette', 'violet') as Palette);
  readonly storage = signal<StorageLocation>(this.db.getSetting('storage.location', 'internal') as StorageLocation);

  readonly feedIntervals: { value: number; label: string }[] = [
    { value: 0, label: 'Jamais' },
    { value: 60, label: '1 h' },
    { value: 360, label: '6 h' },
    { value: 1440, label: '24 h' },
  ];
  // Minutes entre deux actualisations automatiques des flux (0 = désactivé).
  readonly feedInterval = signal<number>(Number(this.db.getSetting('feeds.interval', '360')));

  setFeedInterval(minutes: number): void {
    this.feedInterval.set(minutes);
    this.db.setSetting('feeds.interval', String(minutes));
  }

  readonly mobileDownloads = signal(this.db.getSetting('downloads.mobile', '0') === '1');

  setMobileDownloads(allowed: boolean): void {
    this.mobileDownloads.set(allowed);
    this.db.setSetting('downloads.mobile', allowed ? '1' : '0');
  }

  onMobileData(): boolean {
    return Connectivity.getConnectionType() === Connectivity.connectionType.mobile;
  }

  isLight(): boolean {
    const mode = this.themeMode();
    return mode === 'light' || (mode === 'system' && Application.systemAppearance() === 'light');
  }

  setThemeMode(mode: ThemeMode): void {
    this.themeMode.set(mode);
    this.db.setSetting('theme.mode', mode);
  }

  setPalette(palette: Palette): void {
    this.palette.set(palette);
    this.db.setSetting('theme.palette', palette);
  }

  setStorage(location: StorageLocation): void {
    this.storage.set(location);
    this.db.setSetting('storage.location', location);
  }

  externalAvailable(): boolean {
    return this.externalRoot() !== null;
  }

  // Root folder for new downloads; falls back to internal storage if the external one is unavailable.
  downloadsRoot(): string {
    const root = this.storage() === 'external' ? this.externalRoot() : null;
    const base = root ?? this.internalRoot();
    const dir = new java.io.File(base, 'podcasts');
    dir.mkdirs();
    return String(dir.getAbsolutePath());
  }

  freeSpaceBytes(): number {
    const stat = new android.os.StatFs(this.internalRoot());
    return Number(stat.getAvailableBytes());
  }

  private internalRoot(): string {
    return String(Utils.android.getApplicationContext().getFilesDir().getAbsolutePath());
  }

  private externalRoot(): string | null {
    const dir = Utils.android.getApplicationContext().getExternalFilesDir(null);
    return dir ? String(dir.getAbsolutePath()) : null;
  }
}
