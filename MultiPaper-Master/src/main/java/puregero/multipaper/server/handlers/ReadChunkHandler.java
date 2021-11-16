package puregero.multipaper.server.handlers;

import puregero.multipaper.server.DataOutputSender;
import puregero.multipaper.server.ServerConnection;
import puregero.multipaper.server.locks.ChunkLock;
import puregero.multipaper.server.util.RegionFileCache;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;

public class ReadChunkHandler implements Handler {
    @Override
    public void handle(ServerConnection connection, DataInputStream in, DataOutputSender out) throws IOException {
        String world = in.readUTF();
        String path = in.readUTF();
        int cx = in.readInt();
        int cz = in.readInt();

        if (path.equals("region") || path.equals("entities")) {
            ServerConnection owner = ChunkLock.getOwner(world, cx, cz);
            if (owner != null && owner != connection) {
                out.writeUTF("chunkData");
                out.writeUTF(owner.getBungeeCordName());
                out.writeInt(0);
                out.send();
                return;
            }
        }

        byte[] b = RegionFileCache.getChunkDeflatedData(getWorldDir(world, path), cx, cz);
        if (b == null) {
            b = new byte[0];
        }
        out.writeUTF("chunkData");
        out.writeUTF("");
        out.writeInt(b.length);
        out.write(b);
        out.send();
    }

    static File getWorldDir(String world, String path) {
        File file = new File(world);

        if (world.endsWith("_nether")) {
            file = new File(file, "DIM-1");
        }

        if (world.endsWith("_the_end")) {
            file = new File(file, "DIM1");
        }

        return new File(file, path);
    }
}
