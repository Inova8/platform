import { HttpClientModule } from '@angular/common/http';
import { async, ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatSnackBarModule } from '@angular/material/snack-bar';
import { RouterTestingModule } from '@angular/router/testing';
import { DepInjectorService } from 'src/app/dep-injector.service';
import { Widget } from 'src/app/instance/widget';
import { MappingComponent } from 'src/app/mapping/mapping.component';

import { EditRelatedComponent } from './edit-related.component';

describe('EditRelatedComponent', () => {
  let component: EditRelatedComponent;
  let fixture: ComponentFixture<EditRelatedComponent>;

  beforeEach(async(() => {
    TestBed.configureTestingModule({
      imports: [MatDialogModule, MatSnackBarModule, HttpClientModule, RouterTestingModule],
      declarations: [EditRelatedComponent],
      providers: [MatDialog]
    })
      .compileComponents();
  }));

  beforeEach(() => {
    DepInjectorService.setInjector(TestBed.createComponent(MappingComponent).debugElement.injector);
    fixture = TestBed.createComponent(EditRelatedComponent);
    component = fixture.componentInstance;
    component.layout = {} as Widget;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
