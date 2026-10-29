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

export interface Chapter {
  title: string;
  startMs: number;
}

export interface OnlineDetails {
  asin?: string;
  subtitle?: string;
  summary?: string;
  releaseDate?: string;
  runtimeMin?: number;
  rating?: string;
}

export interface Book extends OnlineDetails {
  id: string;
  title: string;
  tracks: Track[];
  kind?: 'folder' | 'podcast';
  artwork?: string;
  author?: string;
  addedAt?: number;
  series?: string;
  seriesPosition?: string;
  narrators?: string[];
  genres?: string[];
  publisher?: string;
  language?: string;
  chapters?: Chapter[];
}

// Informations d'un livre issues des tags des fichiers puis, si besoin, des API en ligne.
export interface BookTags extends OnlineDetails {
  sig: string;
  version?: number;
  title?: string;
  author?: string;
  artwork?: string;
  names?: string[];
  online?: boolean;
  series?: string;
  seriesPosition?: string;
  narrators?: string[];
  genres?: string[];
  publisher?: string;
  language?: string;
  chapters?: Chapter[];
  durations?: number[];
}

// Incrémenter pour refaire la recherche en ligne des livres déjà traités.
export const TAGS_VERSION = 4;

// Nom de chaque piste d'après les chapitres qui recouvrent sa plage (durées en ms, pistes dans l'ordre).
export function chapterTrackNames(chapters: Chapter[], durations: number[]): (string | undefined)[] {
  let from = 0;
  return durations.map((duration) => {
    const start = from;
    const end = from + duration;
    from = end;
    if (!duration) {
      return undefined;
    }
    const names = chapters
      .filter((chapter, j) => chapter.startMs < end && (chapters[j + 1]?.startMs ?? Infinity) > start)
      .map((chapter) => chapter.title);
    return names.length ? names.join(' · ') : undefined;
  });
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
    return books.map((book) => {
      const tags = this.cachedTags(book);
      return tags ? this.applyTags(book, tags) : book;
    });
  }

  cachedTags(book: Book): BookTags | null {
    const raw = this.db.getSetting(`tags:${book.id}`, '');
    if (!raw) {
      return null;
    }
    try {
      const tags = JSON.parse(raw) as BookTags;
      return tags.sig === this.tagSignature(book) ? tags : null;
    } catch {
      return null;
    }
  }

  saveTags(book: Book, tags: BookTags): void {
    this.db.setSetting(`tags:${book.id}`, JSON.stringify(tags));
  }

  tagSignature(book: Book): string {
    return `${book.tracks.length}:${book.addedAt ?? 0}`;
  }

  applyTags(book: Book, tags: BookTags): Book {
    const chapterNames = tags.chapters && tags.durations?.length === book.tracks.length ? chapterTrackNames(tags.chapters, tags.durations) : [];
    const tracks = book.tracks.map((track, i) => {
      const name = chapterNames[i] ?? tags.names?.[i];
      return name ? { ...track, name } : track;
    });
    return {
      ...book,
      tracks,
      title: tags.title || book.title,
      author: tags.author || book.author,
      artwork: tags.artwork || book.artwork,
      series: tags.series,
      seriesPosition: tags.seriesPosition,
      narrators: tags.narrators,
      genres: tags.genres,
      publisher: tags.publisher,
      language: tags.language,
      chapters: tags.chapters,
      asin: tags.asin,
      subtitle: tags.subtitle,
      summary: tags.summary,
      releaseDate: tags.releaseDate,
      runtimeMin: tags.runtimeMin,
      rating: tags.rating,
    };
  }

  // Lit les tags embarqués (album, artiste, titres des pistes, pochette) ; cède la main entre deux fichiers.
  async readTags(book: Book): Promise<BookTags> {
    const tags: BookTags = { sig: this.tagSignature(book) };
    const context = Utils.android.getApplicationContext();
    const retriever = new android.media.MediaMetadataRetriever();
    const keys = android.media.MediaMetadataRetriever;
    const names: string[] = [];
    try {
      for (let i = 0; i < book.tracks.length; i++) {
        let title = '';
        try {
          retriever.setDataSource(context, android.net.Uri.parse(book.tracks[i].uri));
          title = (retriever.extractMetadata(keys.METADATA_KEY_TITLE) ?? '').trim();
          if (!tags.title) {
            tags.title = (retriever.extractMetadata(keys.METADATA_KEY_ALBUM) ?? '').trim() || undefined;
          }
          if (!tags.author) {
            const author =
              retriever.extractMetadata(keys.METADATA_KEY_ALBUMARTIST) ||
              retriever.extractMetadata(keys.METADATA_KEY_ARTIST) ||
              retriever.extractMetadata(keys.METADATA_KEY_AUTHOR);
            tags.author = author?.trim() || undefined;
          }
          if (!tags.artwork && i < 3) {
            tags.artwork = this.saveCover(book.id, retriever.getEmbeddedPicture());
          }
        } catch {
          // Fichier illisible : on garde son nom.
        }
        names.push(title);
        await new Promise((resolve) => setTimeout(resolve, 0));
      }
    } finally {
      retriever.release();
    }
    // Des titres identiques d'un fichier à l'autre ne distinguent rien : on garde les noms de fichiers.
    if (new Set(names.filter(Boolean)).size > 1 || (names.length === 1 && names[0])) {
      tags.names = names;
    }
    return tags;
  }

  // Durée de chaque piste en ms, pour situer les chapitres API dans les fichiers.
  async readDurations(book: Book): Promise<number[]> {
    const context = Utils.android.getApplicationContext();
    const retriever = new android.media.MediaMetadataRetriever();
    const durations: number[] = [];
    try {
      for (const track of book.tracks) {
        let ms = 0;
        try {
          retriever.setDataSource(context, android.net.Uri.parse(track.uri));
          ms = Number(retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION) ?? 0);
        } catch {
          // Fichier illisible : durée inconnue.
        }
        durations.push(ms);
        await new Promise((resolve) => setTimeout(resolve, 0));
      }
    } finally {
      retriever.release();
    }
    return durations;
  }

  coverPath(bookId: string, suffix = ''): string {
    const dir = new java.io.File(Utils.android.getApplicationContext().getFilesDir(), 'covers');
    dir.mkdirs();
    return `${dir.getAbsolutePath()}/${Math.abs(new java.lang.String(bookId).hashCode())}${suffix ? `-${suffix}` : ''}.jpg`;
  }

  private saveCover(bookId: string, bytes: any): string | undefined {
    if (!bytes || !bytes.length) {
      return undefined;
    }
    const path = this.coverPath(bookId);
    const out = new java.io.FileOutputStream(path);
    try {
      out.write(bytes);
    } finally {
      out.close();
    }
    return path;
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
        [columns.COLUMN_DOCUMENT_ID, columns.COLUMN_DISPLAY_NAME, columns.COLUMN_MIME_TYPE, columns.COLUMN_LAST_MODIFIED],
        null,
        null,
        null,
      );
    if (!cursor) {
      return;
    }
    const tracks: Track[] = [];
    let addedAt = 0;
    const folders: { id: string; name: string }[] = [];
    try {
      while (cursor.moveToNext()) {
        const id = cursor.getString(0);
        const name = cursor.getString(1);
        const mime = cursor.getString(2);
        if (mime === columns.MIME_TYPE_DIR) {
          folders.push({ id, name });
        } else if (/\.(mp3|m4a|m4b|aac|ogg|oga|opus|flac|wav)$/i.test(name) || (mime ?? '').startsWith('audio/')) {
          addedAt = Math.max(addedAt, Number(cursor.getLong(3)));
          tracks.push({ name, uri: docs.buildDocumentUriUsingTree(treeUri, id).toString() });
        }
      }
    } finally {
      cursor.close();
    }

    if (tracks.length) {
      tracks.sort((a, b) => a.name.localeCompare(b.name, undefined, { numeric: true }));
      books.push({
        id: `${treeUri.toString()}|${docId}`,
        title: folderName.replace(/_+/g, ' ').trim(),
        tracks,
        addedAt: addedAt || undefined,
      });
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
