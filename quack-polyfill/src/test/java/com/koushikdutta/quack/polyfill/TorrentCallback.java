package com.koushikdutta.quack.polyfill;

@FunctionalInterface
public interface TorrentCallback {
    void onTorrent(Torrent torrent);
}
