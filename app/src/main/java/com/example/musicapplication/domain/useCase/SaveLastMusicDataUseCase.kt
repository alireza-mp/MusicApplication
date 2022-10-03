package com.example.musicapplication.domain.useCase

import com.example.musicapplication.domain.repostiry.MusicsRepository
import javax.inject.Inject

class SaveLastMusicDataUseCase
@Inject
constructor(
    private val musicsRepository: MusicsRepository,
) {

    suspend operator fun invoke(
        duration: Long,
        currentPosition: Long,
        musicTitle: String,
    ) = musicsRepository.saveLastMusicData(duration, currentPosition, musicTitle)

}