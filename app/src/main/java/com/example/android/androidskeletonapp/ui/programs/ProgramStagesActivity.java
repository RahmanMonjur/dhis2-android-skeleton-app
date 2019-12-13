package com.example.android.androidskeletonapp.ui.programs;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import com.example.android.androidskeletonapp.R;
import com.example.android.androidskeletonapp.data.Sdk;
import com.example.android.androidskeletonapp.data.service.ActivityStarter;
import com.example.android.androidskeletonapp.ui.base.ListActivity;
import com.example.android.androidskeletonapp.ui.events.EventsActivity;
import com.example.android.androidskeletonapp.ui.tracked_entity_instances.TrackedEntityInstancesActivity;

import org.hisp.dhis.android.core.arch.repositories.scope.RepositoryScope;
import org.hisp.dhis.android.core.program.ProgramType;

import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

public class ProgramStagesActivity extends ListActivity implements OnProgramStageSelectionListener {

    private Disposable disposable;
    private String selectedProgramStage;
    private enum IntentExtra {
        PROGRAM,
        TEI
    }

    public static Intent getProgramStagesActivityIntent(Context context, String programUid, String teiUid) {
        Intent intent = new Intent(context, ProgramStagesActivity.class);
        intent.putExtra(IntentExtra.PROGRAM.name(), programUid);
        intent.putExtra(IntentExtra.TEI.name(), teiUid);

        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setUp(R.layout.activity_program_stages, R.id.programsToolbar, R.id.programsRecyclerView);
        observeProgramStages(getIntent().getStringExtra(IntentExtra.PROGRAM.name()), getIntent().getStringExtra(IntentExtra.TEI.name()));
    }

    private void observeProgramStages(String programUid, String teiUid) {
        ProgramStagesAdapter adapter = new ProgramStagesAdapter(this, programUid, teiUid);
        recyclerView.setAdapter(adapter);

        disposable = Sdk.d2().organisationUnitModule().organisationUnits().get()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .map(organisationUnitUids -> Sdk.d2().programModule().programStages().byProgramUid().eq(programUid)
                        .orderByName(RepositoryScope.OrderByDirection.ASC)
                        .getPaged(20))
                .subscribe(programs -> programs.observe(this, programPagedList -> {
                    adapter.submitList(programPagedList);
                    findViewById(R.id.programsNotificator).setVisibility(
                            programPagedList.isEmpty() ? View.VISIBLE : View.GONE);
                }));
    }

    @Override
    public void onProgramStageSelected(String programUid, String programStageUid, String teiUid) {

        ActivityStarter.startActivity(this,
                    EventsActivity
                            .getIntent(this, programUid, programStageUid, teiUid),
                    false);
    }


}
