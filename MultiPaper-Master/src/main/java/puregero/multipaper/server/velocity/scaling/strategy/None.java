package puregero.multipaper.server.velocity.scaling.strategy;

import java.util.concurrent.TimeUnit;

public class None extends Base {
    public None() {
        super(Long.MAX_VALUE, TimeUnit.DAYS);
    }
}
