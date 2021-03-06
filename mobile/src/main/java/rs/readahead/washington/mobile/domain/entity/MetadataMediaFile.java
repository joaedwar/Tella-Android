package rs.readahead.washington.mobile.domain.entity;

import java.io.Serializable;

import rs.readahead.washington.mobile.util.C;


public final class MetadataMediaFile extends RawFile implements Serializable {
    public static MetadataMediaFile newCSV(MediaFile mediaFile) {
        String uid = mediaFile.uid;
        return new MetadataMediaFile(C.METADATA_DIR, uid, uid + ".csv");
    }

    private MetadataMediaFile(String path, String uid, String filename) {
        this.uid = uid;
        this.path = path;
        this.fileName = filename;
    }

    private MetadataMediaFile() {
    }
}
