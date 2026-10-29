import { Injectable, signal } from '@angular/core';
import { Application, Folder, Http, path } from '@nativescript/core';

declare const android: any;
declare const java: any;
declare const androidx: any;

const REPO = 'cgreg21/audiobook-player';

interface ReleaseInfo {
  tag: string;
  url: string;
  notes: string;
}

export type UpdateStatus = 'idle' | 'checking' | 'uptodate' | 'available' | 'downloading' | 'error';

@Injectable({ providedIn: 'root' })
export class UpdateService {
  readonly status = signal<UpdateStatus>('idle');
  readonly latest = signal<ReleaseInfo | null>(null);
  readonly message = signal('');

  currentVersion(): string {
    const context = Application.android.context;
    return String(context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName);
  }

  async check(): Promise<void> {
    this.status.set('checking');
    this.message.set('');
    try {
      const response = await Http.request({
        url: `https://api.github.com/repos/${REPO}/releases?per_page=10`,
        method: 'GET',
        headers: { Accept: 'application/vnd.github+json' },
      });
      const releases = response.content?.toJSON() as any[];
      const release = releases.find((r) => !r.draft && r.assets?.some((a: any) => /\.apk$/.test(a.name)));
      if (!release) {
        this.status.set('uptodate');
        return;
      }
      const asset = release.assets.find((a: any) => /\.apk$/.test(a.name));
      const info = { tag: String(release.tag_name), url: String(asset.browser_download_url), notes: String(release.body ?? '') };
      this.latest.set(info);
      this.status.set(this.compare(info.tag, this.currentVersion()) > 0 ? 'available' : 'uptodate');
    } catch (error) {
      this.fail('Vérification impossible', error);
    }
  }

  async install(): Promise<void> {
    const info = this.latest();
    if (!info) {
      return;
    }
    const context = Application.android.context;
    if (Number(android.os.Build.VERSION.SDK_INT) >= 26 && !context.getPackageManager().canRequestPackageInstalls()) {
      const intent = new android.content.Intent(
        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        android.net.Uri.parse('package:' + context.getPackageName()),
      );
      intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
      context.startActivity(intent);
      this.message.set('Autorisez l’installation depuis cette application, puis relancez la mise à jour.');
      return;
    }

    this.status.set('downloading');
    try {
      const dir = path.join(String(context.getCacheDir().getAbsolutePath()), 'updates');
      Folder.fromPath(dir);
      const target = path.join(dir, 'update.apk');
      await Http.getFile(info.url, target);
      this.launchInstaller(target);
      this.status.set('available');
    } catch (error) {
      this.fail('Téléchargement impossible', error);
    }
  }

  private launchInstaller(file: string): void {
    const context = Application.android.context;
    const uri = androidx.core.content.FileProvider.getUriForFile(
      context,
      context.getPackageName() + '.fileprovider',
      new java.io.File(file),
    );
    const intent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
    intent.setDataAndType(uri, 'application/vnd.android.package-archive');
    intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION | android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
    context.startActivity(intent);
  }

  private fail(prefix: string, error: unknown): void {
    this.status.set('error');
    this.message.set(`${prefix} : ${error instanceof Error ? error.message : String(error)}`);
  }

  private compare(a: string, b: string): number {
    const parse = (v: string) => {
      const [core, pre] = v.replace(/^v/, '').split('-');
      return { nums: core.split('.').map((n) => parseInt(n, 10) || 0), pre: !!pre };
    };
    const x = parse(a);
    const y = parse(b);
    for (let i = 0; i < 3; i++) {
      const diff = (x.nums[i] ?? 0) - (y.nums[i] ?? 0);
      if (diff !== 0) {
        return diff;
      }
    }
    return x.pre === y.pre ? 0 : x.pre ? -1 : 1;
  }
}
