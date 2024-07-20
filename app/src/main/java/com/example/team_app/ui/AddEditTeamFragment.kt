package com.example.team_app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues.TAG
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.speech.RecognizerIntent
import android.text.Editable
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.team_app.MyLifecycleObserver
import com.example.team_app.R
import com.example.team_app.data.model.Team
import com.example.team_app.databinding.AddEditTeamLayoutBinding
import com.example.team_app.ui.adapter.PlayerAdapter
import com.example.team_app.viewmodel.SharedViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.FirebaseApp
import com.google.firebase.functions.FirebaseFunctions

// Fragment to add or edit a team
class AddEditTeamFragment : Fragment() {

    // Backing property for view binding
    private var _binding: AddEditTeamLayoutBinding? = null

    // Public property for accessing the binding, ensuring it's not null
    private val binding get() = _binding!!

    // Shared ViewModel for communication between fragments
    private val sharedViewModel: SharedViewModel by activityViewModels()

    // Adapter for displaying players
    private lateinit var playerAdapter: PlayerAdapter

    // URI of the selected image
    private var imageUri: Uri? = null

    // Firebase Functions instance for sending emails
    private lateinit var functions: FirebaseFunctions

    // Lifecycle observer for handling permissions
    private lateinit var observer: MyLifecycleObserver

    // Variables to store initial data
    private var initialTeamName: String? = null
    private var initialTeamLogoUri: Uri? = null
    private var initialPlayerList: List<String>? = null
    private var initialTeamContactNumber: String? = null
    private var hasChanges = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        context?.let { FirebaseApp.initializeApp(it) }
        functions = FirebaseFunctions.getInstance()
    }

    @SuppressLint("Range")
    private val pickContactLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val contactUri: Uri? = result.data!!.data
            if (contactUri != null) {
                val cursor = requireContext().contentResolver.query(
                    contactUri,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                    null,
                    null,
                    null
                )
                cursor?.let {
                    if (it.moveToFirst()) {
                        val contactNumber =
                            it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER))
                        sharedViewModel.setContactNumber(contactNumber)
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.contact_chosen) + contactNumber,
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.no_contact_selected),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    it.close()
                }
            }
        }
    }

    // Launcher for picking an image from the device
    private val pickImageLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) {
            binding.imageViewTeamLogo.setImageURI(it)
            requireActivity().contentResolver.takePersistableUriPermission(
                it!!, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            imageUri = it
            sharedViewModel.teamLogoUri.value = it
            checkForChanges()
        }

    // Launcher for speech recognition
    private val speechRecognizerLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val spokenText =
                    result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.get(0)
                        ?: ""
                Log.d("AddEditTeamFragment", "Received spoken text: $spokenText")
                binding.editTextTeamName.text =
                    Editable.Factory.getInstance().newEditable(spokenText)
            }
        }

    // Inflate the layout for this fragment
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        this.context?.let { FirebaseApp.initializeApp(it) }
        _binding = AddEditTeamLayoutBinding.inflate(inflater, container, false)
        // Set click listeners for buttons
        binding.buttonSelectPhoto.setOnClickListener { pickImageLauncher.launch(arrayOf("image/*")) }
        binding.buttonAddTeamContact.setOnClickListener {
            val intent =
                Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
            pickContactLauncher.launch(intent)
        }

        return binding.root
    }

    // Set up the view after it has been created
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        functions = FirebaseFunctions.getInstance()

        // Initialize the lifecycle observer for handling permissions
        observer = MyLifecycleObserver(
            requireActivity().activityResultRegistry,
            requireContext()
        )
        lifecycle.addObserver(observer)

        // Set click listener for the speech button
        binding.speechBtn.setOnClickListener {
            observer.checkPermission(Manifest.permission.RECORD_AUDIO) {
                val intent = sharedViewModel.getSpeechRecognizerIntent()
                speechRecognizerLauncher.launch(intent)
            }
        }

        // Observe speech result and update team name
        sharedViewModel.speechResult.observe(viewLifecycleOwner) { result ->
            binding.editTextTeamName.text = Editable.Factory.getInstance().newEditable(result)
        }

        // Handle back press to show unsaved changes dialog if there are changes
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            if (hasChanges) {
                showUnsavedChangesDialog()
            } else {
                findNavController().navigateUp()
            }
        }

        // Initialize the player adapter and set up the RecyclerView
        playerAdapter = PlayerAdapter(mutableListOf())
        binding.recyclerViewPlayers.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = playerAdapter
        }

        // Observe edit mode and update UI accordingly
        sharedViewModel.isEditMode.observe(viewLifecycleOwner) { isEditMode ->
            if (isEditMode == true) {
                // Edit mode
                sharedViewModel.editTeam.value?.let { teamWithPlayers ->
                    val team = teamWithPlayers.team
                    binding.editTextTeamName.setText(team.teamName)
                    team.teamLogoUri.let {
                        binding.imageViewTeamLogo.setImageURI(Uri.parse(it))
                    }
                    binding.editTextTeamEmail.setText(team.teamEmail)
                    binding.editTextTeamEmail.isEnabled = false
                    playerAdapter.updatePlayers(teamWithPlayers.players)

                    // Store initial data for change tracking
                    initialTeamName = team.teamName
                    initialTeamLogoUri = Uri.parse(team.teamLogoUri)
                    initialPlayerList = teamWithPlayers.players.map { it.playerName }
                    initialTeamContactNumber = team.teamContactNumber
                    binding.textViewTeamContactNumber.text = initialTeamContactNumber
                    hasChanges = false
                }
            } else {
                // New team creation mode
                initialTeamName = null
                initialTeamLogoUri = null
                initialPlayerList = null
                initialTeamContactNumber = null
                binding.editTextTeamEmail.isEnabled = true
                hasChanges = false
            }
        }

        // Add text change listeners for tracking changes
        binding.editTextTeamEmail.addTextChangedListener {
            sharedViewModel.teamEmail.value = it.toString()
        }
        binding.editTextTeamName.addTextChangedListener {
            sharedViewModel.teamName.value = it.toString()
        }

        // Observe ViewModel data and update UI
        sharedViewModel.teamName.observe(viewLifecycleOwner) { name ->
            if (binding.editTextTeamName.text.toString() != name) {
                binding.editTextTeamName.setText(name)
            }
        }
        sharedViewModel.teamEmail.observe(viewLifecycleOwner) { email ->
            if (binding.editTextTeamEmail.text.toString() != email) {
                binding.editTextTeamEmail.setText(email)
            }
        }
        sharedViewModel.teamContactNumber.observe(viewLifecycleOwner) { contactNumber ->
            binding.textViewTeamContactNumber.text = contactNumber
        }
        sharedViewModel.teamLogoUri.observe(viewLifecycleOwner) { uri ->
            binding.imageViewTeamLogo.setImageURI(uri)
        }
        sharedViewModel.playerList.observe(viewLifecycleOwner) { players ->
            if (players != null) {
                playerAdapter.updatePlayers(players)
            }
        }

        setupChangeTracking()
        // Set click listener for adding a player
        binding.buttonAddPlayer.setOnClickListener {
            val playerList = sharedViewModel.playerList.value
            if (playerList != null && playerList.size >= 23) {
                showToast(getString(R.string.max_players_reached))
            } else {
                findNavController().navigate(R.id.action_addEditTeamFragment2_to_addPlayerFragment)
            }
        }
        // Set click listener for saving the team
        binding.buttonSaveTeam.setOnClickListener {
            saveTeam()
        }

        // Setup ItemTouchHelper for swipe actions in RecyclerView
        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                return makeMovementFlags(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                return false
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                showDeleteConfirmationDialog(position)
            }
        }).attachToRecyclerView(binding.recyclerViewPlayers)
    }

    // Setup change tracking for detecting unsaved changes
    private fun setupChangeTracking() {
        binding.editTextTeamName.addTextChangedListener {
            checkForChanges()
        }

        sharedViewModel.teamLogoUri.observe(viewLifecycleOwner) {
            checkForChanges()
        }

        sharedViewModel.playerList.observe(viewLifecycleOwner) { players ->
            playerAdapter.updatePlayers(players)
            checkForChanges()
        }
        sharedViewModel.teamContactNumber.observe(viewLifecycleOwner) { contactNumber ->
            binding.textViewTeamContactNumber.text = contactNumber
            checkForChanges()
        }
    }

    // Check if there are any changes in the input fields compared to initial values
    private fun checkForChanges() {
        val currentTeamName = binding.editTextTeamName.text.toString()
        val currentTeamLogoUri = sharedViewModel.teamLogoUri.value
        val currentPlayerList = sharedViewModel.playerList.value?.map { it.playerName }
        val currentTeamContactNumber = sharedViewModel.teamContactNumber.value
        val noContactSelected = getString(R.string.no_contact_selected)

        hasChanges = when {
            sharedViewModel.isEditMode.value == true -> (
                    currentTeamName != initialTeamName ||
                            currentTeamLogoUri != initialTeamLogoUri ||
                            currentPlayerList != initialPlayerList ||
                            (initialTeamContactNumber == getString(R.string.no_contact_selected) && currentTeamContactNumber != "") ||
                            (initialTeamContactNumber != getString(R.string.no_contact_selected) && currentTeamContactNumber != initialTeamContactNumber)
                    )

            else -> (
                    currentTeamName.isNotEmpty() ||
                            currentTeamLogoUri != null ||
                            currentPlayerList?.isNotEmpty() == true ||
                            currentTeamContactNumber != noContactSelected
                    )
        }
    }

    // Save the team to the database and send an email
    private fun saveTeam() {
        val teamName = binding.editTextTeamName.text.toString()
        val teamLogoUri = sharedViewModel.teamLogoUri.value
        val players = sharedViewModel.playerList.value
        val teamEmail = binding.editTextTeamEmail.text.toString()
        val teamContactNumber = sharedViewModel.teamContactNumber.value ?: ""

        if (teamName.isEmpty()) {
            showToast(getString(R.string.team_name_required))
            return
        }

        if (!sharedViewModel.isEditMode.value!! || initialTeamName != teamName) {
            if (!sharedViewModel.isTeamNameUnique(teamName)) {
                showToast(getString(R.string.team_name_unique))
                return
            }
        }

        if (isEnglish(teamName) && !teamName[0].isUpperCase()) {
            showToast(getString(R.string.team_name_capital))
            return
        }

        if (teamName.length > 15) {
            showToast(getString(R.string.team_name_length))
            return
        }

        if (teamLogoUri == null) {
            showToast(getString(R.string.team_logo_required))
            return
        }

        if (players.isNullOrEmpty()) {
            showToast(getString(R.string.player_required))
            return
        }

        if (!isValidEmail(teamEmail)) {
            showToast(getString(R.string.invalid_email))
            return
        }

        if (teamContactNumber == getString(R.string.no_contact_selected)) {
            showToast(getString(R.string.team_contact_required))
            return
        }

        // Disable email editing if not in edit mode
        if (!sharedViewModel.isEditMode.value!!) {
            binding.editTextTeamEmail.isEnabled = false
        }

        // Create a new team object
        val team = Team(
            teamName = teamName,
            teamLogoUri = teamLogoUri.toString(),
            teamEmail = teamEmail,
            teamContactNumber = teamContactNumber
        )

        if (sharedViewModel.isEditMode.value == true) {
            // Update existing team
            team.teamId = sharedViewModel.editTeam.value?.team?.teamId
            sharedViewModel.updateTeam(team)
            if (hasChanges) {
                sendMailToUser(teamName, teamEmail, true)
            }
        } else {
            // Save new team
            sharedViewModel.saveTeam(team)
            sendMailToUser(teamName, teamEmail, false)
        }

        // Clear input fields and reset change tracking
        clearInputFields()
        hasChanges = false
        findNavController().navigate(R.id.action_addEditTeamFragment2_to_allTeamsFragment)
    }

    // Validate email format
    private fun isValidEmail(email: String): Boolean {
        return android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()
    }

    // Show a dialog to warn about unsaved changes
    private fun showUnsavedChangesDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.unsaved_changes_title))
            .setMessage(getString(R.string.unsaved_changes_message))
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.dismiss()
            }
            .setPositiveButton(getString(R.string.leave)) { dialog, _ ->
                dialog.dismiss()
                findNavController().navigateUp()
            }
            .show()
    }

    // Clear input fields and reset ViewModel data
    private fun clearInputFields() {
        binding.editTextTeamName.text?.clear()
        binding.imageViewTeamLogo.setImageURI(null)
        sharedViewModel.teamLogoUri.value = null
        sharedViewModel.teamName.value = ""
        sharedViewModel.setContactNumber("")
        playerAdapter.updatePlayers(emptyList())
        sharedViewModel.resetEditMode()
    }

    // Show a toast message
    private fun showToast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    // Show a confirmation dialog for deleting a player
    private fun showDeleteConfirmationDialog(position: Int) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.delete_player_title))
            .setMessage(getString(R.string.delete_player_message))
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.dismiss()
                binding.recyclerViewPlayers.adapter!!.notifyItemChanged(position)
            }
            .setPositiveButton(getString(R.string.ok)) { dialog, _ ->
                sharedViewModel.removePlayer(position)
                binding.recyclerViewPlayers.adapter!!.notifyItemRemoved(position)
                dialog.dismiss()
            }.show()
    }

    // Send an email to the user when a team is saved or updated
    private fun sendMailToUser(teamName: String, userEmail: String, isEditMode: Boolean) {
        val data = hashMapOf(
            "teamName" to teamName,
            "userEmail" to userEmail,
            "isEditMode" to isEditMode
        )
        functions.getHttpsCallable("sendMailToUser")
            .call(data)
            .addOnSuccessListener {
                Log.d(TAG, "Email sent successfully to user")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error sending email to user", e)
            }
    }

    // Check if the text is in English
    private fun isEnglish(text: String): Boolean {
        return text.all { it.isLetter() && (it in 'A'..'Z' || it in 'a'..'z' || it.isWhitespace()) }
    }

    // Clean up the view binding when the view is destroyed
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
