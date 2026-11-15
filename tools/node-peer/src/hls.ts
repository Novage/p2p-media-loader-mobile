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

/** The absolute URLs of a media playlist's segments, in playlist order. */
export const segmentUrls = (text: string, playlistUrl: string): string[] =>
  text
    .split("\n")
    .map((line) => line.trim())
    .filter((line) => line !== "" && !line.startsWith("#"))
    .map((line) => new URL(line, playlistUrl).href);
