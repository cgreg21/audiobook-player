import { Component, inject, NO_ERRORS_SCHEMA, OnInit, signal } from '@angular/core';
import { NativeScriptCommonModule } from '@nativescript/angular';
import { Dialogs } from '@nativescript/core';
import { AppStateService } from '../core/app-state.service';
import { UpdateService } from '../core/update.service';
import { DatabaseService } from '../data/database.service';
import { CatalogService } from '../library/catalog.service';
import { LibraryService } from '../library/library.service';
import { PodcastService } from '../library/podcast.service';
import { Accent, SettingsService, StorageLocation, ThemeMode } from '../settings/settings.service';

interface FolderInfo {
  uri: string;
  label: string;
}

interface UsageInfo {
  title: string;
  episodes: number;
  bytes: number;
}

@Component({
  selector: 'app-options-tab',
  templateUrl: './options-tab.component.html',
  imports: [NativeScriptCommonModule],
  schemas: [NO_ERRORS_SCHEMA],
})
export class OptionsTabComponent implements OnInit {
  readonly settings = inject(SettingsService);
  readonly updates = inject(UpdateService);
  readonly version = this.updates.currentVersion();
  protected readonly catalog = inject(CatalogService);
  private readonly library = inject(LibraryService);
  private readonly podcasts = inject(PodcastService);
  private readonly database = inject(DatabaseService);
  private readonly state = inject(AppStateService);

  readonly themeChoices: { value: ThemeMode; label: string }[] = [
    { value: 'dark', label: 'Sombre' },
    { value: 'light', label: 'Clair' },
    { value: 'system', label: 'Système' },
  ];
  readonly accentChoices: { value: Accent; label: string }[] = [
    { value: 'violet', label: 'Violet' },
    { value: 'coral', label: 'Corail' },
    { value: 'emerald', label: 'Émeraude' },
    { value: 'blue', label: 'Bleu' },
  ];

  readonly usage = signal<UsageInfo[]>([]);
  readonly usageTotal = signal(0);
  readonly databaseBytes = signal(0);
  readonly freeBytes = signal(0);
  readonly downloadsPath = signal('');
  readonly folders = signal<FolderInfo[]>([]);

  ngOnInit(): void {
    this.refresh();
  }

  refresh(): void {
    const usage = this.podcasts.usage();
    this.usage.set(usage);
    this.usageTotal.set(usage.reduce((sum, u) => sum + u.bytes, 0));
    this.databaseBytes.set(this.database.fileSize());
    this.freeBytes.set(this.settings.freeSpaceBytes());
    this.downloadsPath.set(this.settings.downloadsRoot());
    this.folders.set(this.library.folders().map((uri) => ({ uri, label: this.folderLabel(uri) })));
  }

  formatSize(bytes: number): string {
    const mb = bytes / (1024 * 1024);
    return mb >= 1024 ? `${(mb / 1024).toFixed(2)} Go` : `${mb.toFixed(1)} Mo`;
  }

  setStorage(location: StorageLocation): void {
    if (location === 'external' && !this.settings.externalAvailable()) {
      this.state.showError('Stockage indisponible', 'Le stockage externe n’est pas disponible sur cet appareil.');
      return;
    }
    this.settings.setStorage(location);
    this.refresh();
  }

  async clearDownloads(): Promise<void> {
    const confirmed = await Dialogs.confirm({
      title: 'Supprimer les téléchargements',
      message: 'Supprimer tous les épisodes téléchargés ? Les abonnements sont conservés.',
      okButtonText: 'Supprimer',
      cancelButtonText: 'Annuler',
    });
    if (confirmed) {
      this.podcasts.clearDownloads();
      this.catalog.rebuild();
      this.refresh();
    }
  }

  reloadLibrary(): void {
    try {
      this.catalog.reloadFolders();
    } catch {
      this.state.showError('Actualisation impossible', 'Le dossier n’a pas pu être lu.');
    }
    this.refresh();
  }

  async importFolder(): Promise<void> {
    await this.catalog.importFolder();
    this.refresh();
  }

  async removeFolder(folder: FolderInfo): Promise<void> {
    const confirmed = await Dialogs.confirm({
      title: 'Retirer le dossier',
      message: `Retirer « ${folder.label} » de la bibliothèque ? Les fichiers ne sont pas supprimés.`,
      okButtonText: 'Retirer',
      cancelButtonText: 'Annuler',
    });
    if (confirmed) {
      this.library.removeFolder(folder.uri);
      this.catalog.reloadFolders();
      this.refresh();
    }
  }

  private folderLabel(uri: string): string {
    try {
      return decodeURIComponent(uri.split('/').pop() ?? uri).replace(/^primary:/, '');
    } catch {
      return uri;
    }
  }
}
