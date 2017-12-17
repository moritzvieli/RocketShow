import { Response } from '@angular/http';
import { Injectable } from '@angular/core';
import { ApiService } from './api.service';
import { Observable } from 'rxjs/Observable';

@Injectable()
export class TransportService {

  constructor(private apiService: ApiService) { }

  play(): Observable<null> {
    return this.apiService.post('transport/play', null).map((response: Response) => {
      return null;
    });
  }

  stop(): Observable<null> {
    return this.apiService.post('transport/stop', null).map((response: Response) => {
      return null;
    });
  }

  nextSong(): Observable<null> {
    return this.apiService.post('transport/next-song', null).map((response: Response) => {
      return null;
    });
  }

  previousSong(): Observable<null> {
    return this.apiService.post('transport/previous-song', null).map((response: Response) => {
      return null;
    });
  }

}