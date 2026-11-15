// What a player does with HLS playlists that the core does not do for it:
// fetch them, and find the segments to request.

export type Playlist = {
  /** Where the response came from, after redirects. Its URIs resolve against it. */
  url: string;
  /** What was asked for, where a redirect made it differ from `url`. */
  requestedUrl?: string;
  /** The playlist text: what `processManifest` takes as `data`. */
  data: string;
};

export const fetchPlaylist = async (url: string): Promise<Playlist> => {
  const response = await fetch(url);
  if (!response.ok) {
    throw new Error(`${url}: HTTP ${response.status}`);
  }
  return {
    url: response.url,
    requestedUrl: response.url === url ? undefined : url,
    data: await response.text(),
  };
};

export type MediaSegment = {
  /** Absolute URL. */
  url: string;
  /** Seconds, from `EXTINF`. */
  duration: number;
  /** Media sequence number. */
  sequence: number;
  /** Absolute URL of the `EXT-X-MAP` in effect for the segment, if any. */
  initUrl?: string;
};

export type MediaPlaylist = {
  /** `EXT-X-TARGETDURATION`: the longest segment, and the refresh interval. */
  targetDuration: number;
  /** `EXT-X-MEDIA-SEQUENCE`: the sequence number of the first segment. */
  mediaSequence: number;
  /** `EXT-X-ENDLIST`: no segment will be added. */
  endList: boolean;
  segments: MediaSegment[];
};

/**
 * The tags of a media playlist a player needs to play it. Byte ranges, keys
 * and LL-HLS parts are outside the scope; their tags are skipped.
 */
export const parseMediaPlaylist = (
  text: string,
  playlistUrl: string,
): MediaPlaylist => {
  const playlist: MediaPlaylist = {
    targetDuration: 0,
    mediaSequence: 0,
    endList: false,
    segments: [],
  };
  const resolve = (uri: string) => new URL(uri, playlistUrl).href;
  let duration = 0;
  let initUrl: string | undefined;

  for (const raw of text.split("\n")) {
    const line = raw.trim();
    if (line === "") continue;
    if (!line.startsWith("#")) {
      playlist.segments.push({
        url: resolve(line),
        duration,
        sequence: playlist.mediaSequence + playlist.segments.length,
        initUrl,
      });
      continue;
    }
    const colon = line.indexOf(":");
    const tag = colon === -1 ? line : line.slice(0, colon);
    const value = line.slice(colon + 1);
    switch (tag) {
      case "#EXTINF":
        duration = Number(value.split(",")[0]);
        break;
      case "#EXT-X-TARGETDURATION":
        playlist.targetDuration = Number(value);
        break;
      case "#EXT-X-MEDIA-SEQUENCE":
        playlist.mediaSequence = Number(value);
        break;
      case "#EXT-X-ENDLIST":
        playlist.endList = true;
        break;
      case "#EXT-X-MAP":
        initUrl = resolve(/URI="([^"]*)"/.exec(value)![1]);
        break;
    }
  }
  return playlist;
};
