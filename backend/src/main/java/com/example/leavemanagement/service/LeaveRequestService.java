package com.example.leavemanagement.service;

import com.example.leavemanagement.dto.CreateLeaveRequestDto;
import com.example.leavemanagement.exception.InvalidLeaveRequestException;
import com.example.leavemanagement.exception.ResourceNotFoundException;
import com.example.leavemanagement.model.Employee;
import com.example.leavemanagement.model.LeaveRequest;
import com.example.leavemanagement.model.LeaveStatus;
import com.example.leavemanagement.model.LeaveType;
import com.example.leavemanagement.repository.EmployeeRepository;
import com.example.leavemanagement.repository.LeaveRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class LeaveRequestService {

    private final EmployeeRepository employeeRepository;
    private final LeaveRequestRepository leaveRequestRepository;

    public LeaveRequestService(
            EmployeeRepository employeeRepository,
            LeaveRequestRepository leaveRequestRepository) {
        this.employeeRepository = employeeRepository;
        this.leaveRequestRepository = leaveRequestRepository;
    }

    public List<LeaveRequest> getAll() {
        return leaveRequestRepository.findAllByOrderByStartDateDesc();
    }

    public List<LeaveRequest> searchByEmployeeName(String name) {
        return leaveRequestRepository.findByEmployee_NameContaining(name);
    }

    @Transactional
    public LeaveRequest create(CreateLeaveRequestDto dto) {
        if (dto.getStartDate().isAfter(dto.getEndDate())) {
            throw new InvalidLeaveRequestException(
                    "Start date must not be after end date");
        }

        Employee employee = employeeRepository.findById(dto.getEmployeeId())
                .orElseThrow(() ->
                        new ResourceNotFoundException("Employee not found"));

        long requestedDays = ChronoUnit.DAYS.between(
                dto.getStartDate(), dto.getEndDate()) + 1;

        if (requestedDays > Integer.MAX_VALUE) {
            throw new InvalidLeaveRequestException(
                    "Date range is too large");
        }

        int days = (int) requestedDays;

        int used = leaveRequestRepository
                .findByEmployeeIdAndTypeAndStatus(
                        dto.getEmployeeId(),
                        LeaveType.VACATION,
                        LeaveStatus.APPROVED)
                .stream()
                .mapToInt(LeaveRequest::getDays)
                .sum();

        if (dto.getType() == LeaveType.VACATION
                && days > employee.getAnnualQuota()) {
            throw new InvalidLeaveRequestException(
                    "Not enough vacation balance");
        }

        LeaveRequest request = new LeaveRequest();
        request.setEmployeeId(dto.getEmployeeId());
        request.setType(dto.getType());
        request.setStartDate(dto.getStartDate());
        request.setEndDate(dto.getEndDate());
        request.setDays(days);
        request.setStatus(LeaveStatus.PENDING);

        return leaveRequestRepository.save(request);
    }
}
