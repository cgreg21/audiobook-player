import { inject, Injectable } from '@angular/core';
import { Application, Utils } from '@nativescript/core';
import { DatabaseService } from '../data/database.service';

export interface Track {
  id?: string;
  name: string;
  uri: string;
  remoteUrl?: string;
  downloaded?: boolean;
}

export interface Book {
  id: string;
  title: string;
  tracks: Track[];
  kind?: 'folder' | 'podcast';
  artwork?: string;
}

const PICK_REQUEST = 4201;
export const PODCASTS_DIR = 'Podcasts';
const READ_FLAGS = 1 | 2 | 0x40; // read | write | persistable

@Injectable({ providedIn: 'root' })
export class LibraryService {
  private readonly db = inject(DatabaseService);

  async importFolder(): Promise<Book[]> {
    const treeUri = await this.pickFolder();
    if (!treeUri) {
      return [];
    }
    this.savedTreeUris().forEach((uri) => this.db.removeTreeUri(uri));
    this.db.addTreeUri(treeUri);
    return this.loadBooks();
  }

  loadBooks(): Book[] {
    const books: Book[] = [];
    for (const uri of this.savedTreeUris()) {
      try {
        this.scanTree(uri, books);
      } catch {
        // Folder removed or permission revoked: ignore it.
      }
    }
    return books;
  }

  folders(): string[] {
    return this.db.loadTreeUris();
  }

  removeFolder(uri: string): void {
    this.db.removeTreeUri(uri);
  }

  private savedTreeUris(): string[] {
    return this.db.loadTreeUris();
  }

  private pickFolder(): Promise<string | null> {
    return new Promise((resolve) => {
      const onResult = (args: any) => {
        if (args.requestCode !== PICK_REQUEST) {
          return;
        }
        Application.android.off('activityResult', onResult);
        const uri = args.intent?.getData();
        if (args.resultCode !== android.app.Activity.RESULT_OK || !uri) {
          resolve(null);
          return;
        }
        Utils.android
          .getApplicationContext()
          .getContentResolver()
          .takePersistableUriPermission(uri, 3);
        resolve(uri.toString());
      };
      Application.android.on('activityResult', onResult);
      const intent = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE);
      intent.addFlags(READ_FLAGS);
      Application.android.foregroundActivity.startActivityForResult(intent, PICK_REQUEST);
    });
  }

  private scanTree(treeUriString: string, books: Book[]): void {
    const treeUri = android.net.Uri.parse(treeUriString);
    const rootId = android.provider.DocumentsContract.getTreeDocumentId(treeUri);
    this.scanFolder(treeUri, rootId, rootId.split(/[:/]/).pop() || 'Livre audio', books);
  }

  private scanFolder(treeUri: android.net.Uri, docId: string, folderName: string, books: Book[]): void {
    const docs = android.provider.DocumentsContract;
    const columns = docs.Document;
    const cursor = Utils.android
      .getApplicationContext()
      .getContentResolver()
      .query(
        docs.buildChildDocumentsUriUsingTree(treeUri, docId),
        [columns.COLUMN_DOCUMENT_ID, columns.COLUMN_DISPLAY_NAME, columns.COLUMN_MIME_TYPE],
        null,
        null,
        null,
      );
    if (!cursor) {
      return;
    }
    const tracks: Track[] = [];
    const folders: { id: string; name: string }[] = [];
    try {
      while (cursor.moveToNext()) {
        const id = cursor.getString(0);
        const name = cursor.getString(1);
        const mime = cursor.getString(2);
        if (mime === columns.MIME_TYPE_DIR) {
          folders.push({ id, name });
        } else if (/\.mp3$/i.test(name) || mime === 'audio/mpeg') {
          tracks.push({ name, uri: docs.buildDocumentUriUsingTree(treeUri, id).toString() });
        }
      }
    } finally {
      cursor.close();
    }

    if (tracks.length) {
      tracks.sort((a, b) => a.name.localeCompare(b.name, undefined, { numeric: true }));
      books.push({ id: `${treeUri.toString()}|${docId}`, title: folderName.replace(/_+/g, ' ').trim(), tracks });
    }
    for (const folder of folders) {
      if (folder.name === PODCASTS_DIR) {
        continue;
      }
      this.scanFolder(treeUri, folder.id, folder.name, books);
    }
  }

  // Creates <library>/Podcasts/<feed>/<file> in the imported folder; null if no writable folder.
  createPodcastFile(feedFolder: string, fileName: string, mime: string): string | null {
    const treeString = this.savedTreeUris()[0];
    if (!treeString) {
      return null;
    }
    try {
      const docs = android.provider.DocumentsContract;
      const resolver = Utils.android.getApplicationContext().getContentResolver();
      const tree = android.net.Uri.parse(treeString);
      const root = docs.getTreeDocumentId(tree);
      const podcasts = this.childDirectory(resolver, tree, root, PODCASTS_DIR);
      const feed = this.childDirectory(resolver, tree, podcasts, feedFolder);
      const created = docs.createDocument(resolver, docs.buildDocumentUriUsingTree(tree, feed), mime, fileName);
      return created ? created.toString() : null;
    } catch {
      return null;
    }
  }

  private childDirectory(resolver: android.content.ContentResolver, tree: android.net.Uri, parentId: string, name: string): string {
    const docs = android.provider.DocumentsContract;
    const columns = docs.Document;
    const cursor = resolver.query(
      docs.buildChildDocumentsUriUsingTree(tree, parentId),
      [columns.COLUMN_DOCUMENT_ID, columns.COLUMN_DISPLAY_NAME, columns.COLUMN_MIME_TYPE],
      null,
      null,
      null,
    );
    try {
      while (cursor && cursor.moveToNext()) {
        if (cursor.getString(1) === name && cursor.getString(2) === columns.MIME_TYPE_DIR) {
          return cursor.getString(0);
        }
      }
    } finally {
      cursor?.close();
    }
    const created = docs.createDocument(resolver, docs.buildDocumentUriUsingTree(tree, parentId), columns.MIME_TYPE_DIR, name);
    return docs.getDocumentId(created);
  }
}
