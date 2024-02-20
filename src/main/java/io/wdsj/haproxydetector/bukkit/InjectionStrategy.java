package io.wdsj.haproxydetector.bukkit;

public interface InjectionStrategy {
    void inject() throws ReflectiveOperationException;

    void uninject() throws ReflectiveOperationException;
}
