package com.warden.xp;

public interface ExperienceOrbEntityAccessor {
    void warden$setXpSource(String source);
    void warden$setXpContext(String context);
    String warden$getXpSource();
    String warden$getXpContext();
}
