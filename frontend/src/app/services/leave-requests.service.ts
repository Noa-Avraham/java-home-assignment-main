import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { LeaveRequest } from '../models/leave-request.model';

@Injectable({ providedIn: 'root' })
export class LeaveRequestsService {
  private readonly http = inject(HttpClient);
  private readonly apiUrl = '/api/leave-requests';

  getAll(): Observable<LeaveRequest[]> {
    return this.http.get<LeaveRequest[]>(this.apiUrl);
  }

  approve(id: number): Observable<LeaveRequest> {
    return this.http.post<LeaveRequest>(
      `${this.apiUrl}/${id}/approve`,
      {}
    );
  }
}
