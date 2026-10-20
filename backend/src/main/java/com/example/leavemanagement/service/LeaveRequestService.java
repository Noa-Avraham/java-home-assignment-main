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

import java.time.LocalDate;
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

        if (dto.getType() == LeaveType.VACATION) {
            validateVacationBalance(employee, dto);
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

    private void validateVacationBalance(
            Employee employee, CreateLeaveRequestDto dto) {

        List<LeaveRequest> approved = leaveRequestRepository
                .findByEmployeeIdAndTypeAndStatus(
                        employee.getId(),
                        LeaveType.VACATION,
                        LeaveStatus.APPROVED);

        for (int year = dto.getStartDate().getYear();
             year <= dto.getEndDate().getYear();
             year++) {

            int currentYear = year;
            long used = approved.stream()
                    .mapToLong(request -> daysInYear(
                            request.getStartDate(),
                            request.getEndDate(),
                            currentYear))
                    .sum();

            long requested = daysInYear(
                    dto.getStartDate(), dto.getEndDate(), year);

            if (used + requested > employee.getAnnualQuota()) {
                throw new InvalidLeaveRequestException(
                        "Not enough vacation balance");
            }
        }
    }

    private long daysInYear(LocalDate startDate, LocalDate endDate, int year) {
        LocalDate yearStart = LocalDate.of(year, 1, 1);
        LocalDate yearEnd = LocalDate.of(year, 12, 31);
        LocalDate overlapStart = startDate.isAfter(yearStart) ? startDate : yearStart;
        LocalDate overlapEnd = endDate.isBefore(yearEnd) ? endDate : yearEnd;

        if (overlapStart.isAfter(overlapEnd)) {
            return 0;
        }

        return ChronoUnit.DAYS.between(overlapStart, overlapEnd) + 1;
    }
}
