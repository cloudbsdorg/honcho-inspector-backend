package com.revytechinc.honchoinspector.auth.repo;

import com.revytechinc.honchoinspector.auth.entity.ProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProfileRepository extends JpaRepository<ProfileEntity, String> {

    List<ProfileEntity> findByUserId(String userId);

    Optional<ProfileEntity> findByUserIdAndLabel(String userId, String label);

    long countByUserId(String userId);
}
