package ac.thorium.mc.plugin.transport;

import ac.thorium.mc.proto.Hello;

public interface HelloSupplier {
    Hello buildHello();
}
