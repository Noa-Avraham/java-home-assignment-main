package com.example.leavemanagement.repository;

import com.example.leavemanagement.model.LeaveRequest;
import com.example.leavemanagement.model.LeaveStatus;
import com.example.leavemanagement.model.LeaveType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, Long> {

    @Query("""
            select r.employeeId
            from LeaveRequest r
            where r.id = :requestId
            """)
    Optional<Long> findEmployeeIdByRequestId(@Param("requestId") Long requestId);

    List<LeaveRequest> findAllByOrderByStartDateDesc();

    List<LeaveRequest> findByEmployee_NameContaining(String name);

    List<LeaveRequest> findByEmployeeIdAndTypeAndStatus(Long employeeId, LeaveType type, LeaveStatus status);
}
