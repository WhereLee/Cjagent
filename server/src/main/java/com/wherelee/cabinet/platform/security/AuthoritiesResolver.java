package com.wherelee.cabinet.platform.security;

import java.util.List;

public interface AuthoritiesResolver {

    List<String> resolve(Long subjectId);
}
