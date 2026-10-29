import { Injectable } from '@angular/core';
import { Http } from '@nativescript/core';
import type { Chapter } from './library.service';

export interface BookInfo {
  asin?: string;
  title?: string;
  author?: string;
  coverUrl?: string;
  narrators?: string[];
  series?: string;
  seriesPosition?: string;
  genres?: string[];
  publisher?: string;
  language?: string;
  chapters?: Chapter[];
}

const REGION = 'fr';

@Injectable({ providedIn: 'root' })
export class BookInfoService {
  // Complète les infos manquantes : Audnexus (ASIN trouvé via le catalogue Audible), puis Open Library, puis iTunes.
  async lookup(title: string, author: string | undefined, need: { author: boolean; cover: boolean }): Promise<BookInfo> {
    const found: BookInfo = {};
    const sources = [
      () => this.audnexus(title, author),
      () => this.openLibrary(title, author),
      () => this.itunes(title, author),
    ];
    for (const source of sources) {
      if ((!need.author || found.author) && (!need.cover || found.coverUrl) && found.asin) {
        break;
      }
      try {
        const info = await source();
        if (info) {
          this.merge(found, info);
        }
      } catch {
        // Source indisponible : on passe à la suivante.
      }
    }
    return found;
  }

  private merge(found: BookInfo, info: BookInfo): void {
    found.title ||= info.title;
    found.author ||= info.author;
    found.coverUrl ||= info.coverUrl;
    found.asin ||= info.asin;
    found.narrators ||= info.narrators;
    found.series ||= info.series;
    found.seriesPosition ||= info.seriesPosition;
    found.genres ||= info.genres;
    found.publisher ||= info.publisher;
    found.language ||= info.language;
    found.chapters ||= info.chapters;
  }

  async downloadCover(url: string, path: string): Promise<boolean> {
    try {
      await Http.getFile(url, path);
      return true;
    } catch {
      return false;
    }
  }

  private async audnexus(title: string, author?: string): Promise<BookInfo | null> {
    const query = `title=${encodeURIComponent(title)}${author ? `&author=${encodeURIComponent(author)}` : ''}`;
    const search = await this.getJson(
      `https://api.audible.${REGION}/1.0/catalog/products?num_results=5&products_sort_by=Relevance&${query}`,
    );
    const product = (search?.products ?? []).find((p: any) => this.similar(p.title, title));
    if (!product?.asin) {
      return null;
    }
    const asin: string = product.asin;
    const book = await this.getJson(`https://api.audnex.us/books/${asin}?region=${REGION}`);
    if (!book) {
      return null;
    }
    return {
      asin,
      title: book.title,
      author: book.authors?.[0]?.name,
      coverUrl: book.image,
      narrators: this.names(book.narrators),
      series: book.seriesPrimary?.name,
      seriesPosition: book.seriesPrimary?.position?.toString(),
      genres: this.names(book.genres?.filter((g: any) => !g.type || g.type === 'genre')),
      publisher: book.publisherName,
      language: book.language,
      chapters: await this.audnexusChapters(asin),
    };
  }

  private async audnexusChapters(asin: string): Promise<Chapter[] | undefined> {
    const data = await this.getJson(`https://api.audnex.us/books/${asin}/chapters?region=${REGION}`);
    const chapters: Chapter[] = (data?.chapters ?? [])
      .map((c: any) => ({ title: String(c.title ?? '').trim(), startMs: Number(c.startOffsetMs ?? 0) }))
      .filter((c: Chapter) => c.title);
    return chapters.length ? chapters : undefined;
  }

  private names(list: { name?: string }[] | undefined): string[] | undefined {
    const names = (list ?? []).map((item) => item.name ?? '').filter(Boolean);
    return names.length ? names : undefined;
  }

  private async openLibrary(title: string, author?: string): Promise<BookInfo | null> {
    const query = `title=${encodeURIComponent(title)}${author ? `&author=${encodeURIComponent(author)}` : ''}`;
    const data = await this.getJson(`https://openlibrary.org/search.json?${query}&limit=5&fields=title,author_name,cover_i`);
    const doc = (data?.docs ?? []).find((d: any) => this.similar(d.title, title));
    if (!doc) {
      return null;
    }
    return {
      title: doc.title,
      author: doc.author_name?.[0],
      coverUrl: doc.cover_i ? `https://covers.openlibrary.org/b/id/${doc.cover_i}-L.jpg` : undefined,
    };
  }

  private async itunes(title: string, author?: string): Promise<BookInfo | null> {
    const term = encodeURIComponent(author ? `${title} ${author}` : title);
    const data = await this.getJson(
      `https://itunes.apple.com/search?term=${term}&media=audiobook&entity=audiobook&country=${REGION}&limit=5`,
    );
    const item = (data?.results ?? []).find((r: any) => this.similar(r.collectionName, title));
    if (!item) {
      return null;
    }
    return {
      title: item.collectionName,
      author: item.artistName,
      coverUrl: item.artworkUrl100?.replace('100x100', '600x600'),
    };
  }

  private async getJson(url: string): Promise<any> {
    const response = await Http.request({ url, method: 'GET', timeout: 10000 });
    if (response.statusCode < 200 || response.statusCode >= 300) {
      return null;
    }
    return response.content?.toJSON();
  }

  // Évite d'associer un livre à un résultat sans rapport : au moins 60 % des mots du titre cherché doivent se retrouver.
  private similar(candidate: string | undefined, wanted: string): boolean {
    if (!candidate) {
      return false;
    }
    const tokens = (text: string) =>
      text
        .normalize('NFD')
        .replace(/[\u0300-\u036f]/g, '')
        .toLowerCase()
        .split(/[^a-z0-9]+/)
        .filter((t) => t.length > 1);
    const have = new Set(tokens(candidate));
    const want = tokens(wanted);
    return want.length > 0 && want.filter((t) => have.has(t)).length / want.length >= 0.6;
  }
}
