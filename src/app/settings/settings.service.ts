import { inject, Injectable, signal } from '@angular/core';
import { Application, Connectivity, Utils } from '@nativescript/core';
import { DatabaseService } from '../data/database.service';

declare const android: any;
declare const java: any;

export type ThemeMode = 'system' | 'dark' | 'light';
export type Accent = 'violet' | 'coral' | 'emerald' | 'blue';
export type StorageLocation = 'internal' | 'external';

@Injectable({ providedIn: 'root' })
export class SettingsService {
  private readonly db = inject(DatabaseService);

  readonly themeMode = signal<ThemeMode>(this.db.getSetting('theme.mode', 'dark') as ThemeMode);
  readonly accent = signal<Accent>(this.db.getSetting('theme.accent', 'violet') as Accent);
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

  setAccent(accent: Accent): void {
    this.accent.set(accent);
    this.db.setSetting('theme.accent', accent);
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
