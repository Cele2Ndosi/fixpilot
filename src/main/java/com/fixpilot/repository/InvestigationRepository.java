package com.fixpilot.repository;

import com.fixpilot.model.Investigation;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Responsibility (single):
 * - Hold Investigation objects for the life of the process. Deliberately a
 *   ConcurrentHashMap, not JPA/Postgres, for the hackathon build - nothing
 *   outside this class knows or cares, so swapping in a real database
 *   later means changing this one file, not the orchestrator or either
 *   controller.
 */
@Repository
public class InvestigationRepository {

    private final Map<String, Investigation> store = new ConcurrentHashMap<>();

    public Investigation save(Investigation investigation) {
        store.put(investigation.getId(), investigation);
        return investigation;
    }

    public Optional<Investigation> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    public Collection<Investigation> findAll() {
        return store.values();
    }
}
