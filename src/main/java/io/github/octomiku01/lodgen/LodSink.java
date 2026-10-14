package io.github.octomiku01.lodgen;

/** Delivers generated LODs to one player. */
interface LodSink {
    /**
     * @return false if the chunk was not delivered and should be tried again later
     */
    boolean accept(LodChunk chunk);

    /** For measuring generation speed without Voxy (benchmark mode) */
    LodSink DISCARD = chunk -> true;
}
