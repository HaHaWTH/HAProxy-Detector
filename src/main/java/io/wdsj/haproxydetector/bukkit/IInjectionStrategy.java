package io.wdsj.haproxydetector.bukkit;

public interface IInjectionStrategy {
    void inject() throws ReflectiveOperationException;

    void uninject() throws ReflectiveOperationException;
}
