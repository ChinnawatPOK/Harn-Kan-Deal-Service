package com.harnkan.deal.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.harnkan.deal.domain.Deal;

@Repository
public interface DealRepository extends JpaRepository<Deal, String> {
}
