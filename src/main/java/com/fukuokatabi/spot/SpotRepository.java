package com.fukuokatabi.spot;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SpotRepository extends JpaRepository<Spot, String> {
    List<Spot> findByCategory(Spot.Category category);
}
