package com.inkspire.ebookreader.di

import androidx.room.Room
import com.inkspire.ebookreader.data.database.LocalBookDatabase
import com.inkspire.ebookreader.data.repository.BookRepositoryImpl
import com.inkspire.ebookreader.data.repository.ChapterRepositoryImpl
import com.inkspire.ebookreader.data.repository.DatastoreRepositoryImpl
import com.inkspire.ebookreader.data.repository.HiddenTextRepositoryImpl
import com.inkspire.ebookreader.data.repository.HighlightRepositoryImpl
import com.inkspire.ebookreader.data.repository.ImagePathRepositoryImpl
import com.inkspire.ebookreader.data.repository.MusicPathRepositoryImpl
import com.inkspire.ebookreader.data.repository.NoteRepositoryImpl
import com.inkspire.ebookreader.data.repository.TableOfContentRepositoryImpl
import com.inkspire.ebookreader.domain.repository.BookRepository
import com.inkspire.ebookreader.domain.repository.ChapterRepository
import com.inkspire.ebookreader.domain.repository.DatastoreRepository
import com.inkspire.ebookreader.domain.repository.HiddenTextRepository
import com.inkspire.ebookreader.domain.repository.HighlightRepository
import com.inkspire.ebookreader.domain.repository.ImagePathRepository
import com.inkspire.ebookreader.domain.repository.MusicPathRepository
import com.inkspire.ebookreader.domain.repository.NoteRepository
import com.inkspire.ebookreader.domain.repository.TableOfContentRepository
import com.inkspire.ebookreader.domain.usecase.AutoScrollDatastoreUseCase
import com.inkspire.ebookreader.domain.usecase.AutoScrollSettingDatastoreUseCase
import com.inkspire.ebookreader.domain.usecase.BookCategorySettingUseCase
import com.inkspire.ebookreader.domain.usecase.BookContentStylingDatastoreUseCase
import com.inkspire.ebookreader.domain.usecase.BookContentUseCase
import com.inkspire.ebookreader.domain.usecase.BookDetailUseCase
import com.inkspire.ebookreader.domain.usecase.BookmarkSettingDatastoreUseCase
import com.inkspire.ebookreader.domain.usecase.LibraryDatastoreUseCase
import com.inkspire.ebookreader.domain.usecase.LibraryUseCase
import com.inkspire.ebookreader.domain.usecase.MusicSettingDatastoreUseCase
import com.inkspire.ebookreader.domain.usecase.MusicSettingUseCase
import com.inkspire.ebookreader.domain.usecase.MusicUseCase
import com.inkspire.ebookreader.domain.usecase.RecentBookUseCase
import com.inkspire.ebookreader.domain.usecase.SettingDatastoreUseCase
import com.inkspire.ebookreader.domain.usecase.TTSContentUseCase
import com.inkspire.ebookreader.domain.usecase.TTSDatastoreUseCase
import com.inkspire.ebookreader.domain.usecase.TTSSettingDataStoreUseCase
import com.inkspire.ebookreader.domain.usecase.TableOfContentUseCase
import com.inkspire.ebookreader.ui.bookcontent.autoscroll.AutoScrollViewModel
import com.inkspire.ebookreader.ui.bookcontent.bottombar.BookContentBottomBarViewModel
import com.inkspire.ebookreader.ui.bookcontent.bottombar.autoscroll.BottomBarAutoScrollViewModel
import com.inkspire.ebookreader.ui.bookcontent.bottombar.tts.BottomBarTTSViewModel
import com.inkspire.ebookreader.ui.bookcontent.chaptercontent.BookChapterContentViewModel
import com.inkspire.ebookreader.ui.bookcontent.drawer.DrawerViewModel
import com.inkspire.ebookreader.ui.bookcontent.drawer.bookmark.BookmarkViewModel
import com.inkspire.ebookreader.ui.bookcontent.drawer.note.NoteViewModel
import com.inkspire.ebookreader.ui.bookcontent.drawer.tableofcontent.TableOfContentViewModel
import com.inkspire.ebookreader.ui.bookcontent.root.BookContentDataViewModel
import com.inkspire.ebookreader.ui.bookcontent.styling.BookContentStylingViewModel
import com.inkspire.ebookreader.ui.bookcontent.topbar.BookContentTopBarViewModel
import com.inkspire.ebookreader.ui.bookcontent.tts.TTSManager
import com.inkspire.ebookreader.ui.bookcontent.tts.TTSViewModel
import com.inkspire.ebookreader.ui.bookdetail.BookDetailViewModel
import com.inkspire.ebookreader.ui.home.libary.LibraryViewModel
import com.inkspire.ebookreader.ui.home.recentbook.RecentBookViewModel
import com.inkspire.ebookreader.ui.setting.SettingViewModel
import com.inkspire.ebookreader.ui.setting.autoscroll.AutoScrollSettingViewModel
import com.inkspire.ebookreader.ui.setting.bookcategory.BookCategorySettingViewModel
import com.inkspire.ebookreader.ui.setting.bookmark.BookmarkSettingViewModel
import com.inkspire.ebookreader.ui.setting.hiddentext.HiddenTextViewModel
import com.inkspire.ebookreader.ui.setting.music.MusicSettingViewModel
import com.inkspire.ebookreader.ui.setting.tts.TTSSettingViewModel
import com.inkspire.ebookreader.ui.sharedviewmodel.AsyncImportBookViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.bind
import org.koin.dsl.module

object KoinModule {
    val databaseModule = module {
        single(createdAtStart = true) {
            Room
                .databaseBuilder(
                    context = get(),
                    klass = LocalBookDatabase::class.java,
                    name = LocalBookDatabase.DATABASE_NAME
                )
                .fallbackToDestructiveMigration(false)
                .build()
        }
        single { get<LocalBookDatabase>().bookDao }
        single { get<LocalBookDatabase>().libraryDao }
        single { get<LocalBookDatabase>().chapterDao }
        single { get<LocalBookDatabase>().tableOfContentDao }
        single { get<LocalBookDatabase>().imagePathDao }
        single { get<LocalBookDatabase>().musicPathDao }
        single { get<LocalBookDatabase>().noteDao }
        single { get<LocalBookDatabase>().highlightDao }
        single { get<LocalBookDatabase>().hiddenTextDao }
    }

    val repositoryModule = module {
        singleOf(::BookRepositoryImpl).bind<BookRepository>()
        singleOf(::ChapterRepositoryImpl).bind<ChapterRepository>()
        singleOf(::TableOfContentRepositoryImpl).bind<TableOfContentRepository>()
        singleOf(::ImagePathRepositoryImpl).bind<ImagePathRepository>()
        singleOf(::MusicPathRepositoryImpl).bind<MusicPathRepository>()
        singleOf(::NoteRepositoryImpl).bind<NoteRepository>()
        singleOf(::DatastoreRepositoryImpl).bind<DatastoreRepository>()
        singleOf(::HighlightRepositoryImpl).bind<HighlightRepository>()
        singleOf(::HiddenTextRepositoryImpl).bind<HiddenTextRepository>()
    }

    val useCaseModule = module {
        factoryOf(::RecentBookUseCase)
        factoryOf(::LibraryUseCase)
        factoryOf(::LibraryDatastoreUseCase)
        factoryOf(::SettingDatastoreUseCase)
        factoryOf(::AutoScrollDatastoreUseCase)
        factoryOf(::AutoScrollSettingDatastoreUseCase)
        factoryOf(::TTSSettingDataStoreUseCase)
        factoryOf(::BookCategorySettingUseCase)
        factoryOf(::BookmarkSettingDatastoreUseCase)
        factoryOf(::MusicSettingUseCase)
        factoryOf(::MusicSettingDatastoreUseCase)
        factoryOf(::BookDetailUseCase)
        factoryOf(::BookContentUseCase)
        factoryOf(::BookContentStylingDatastoreUseCase)
        factoryOf(::TableOfContentUseCase)
        factoryOf(::TTSContentUseCase)
        factoryOf(::TTSDatastoreUseCase)
        factoryOf(::MusicUseCase)
    }

    val viewModelModule = module {
        viewModelOf(::LibraryViewModel)
        viewModelOf(::AsyncImportBookViewModel)
        viewModelOf(::SettingViewModel)
        viewModelOf(::AutoScrollSettingViewModel)
        viewModelOf(::TTSSettingViewModel)
        viewModelOf(::BookCategorySettingViewModel)
        viewModelOf(::BookmarkSettingViewModel)
        viewModelOf(::MusicSettingViewModel)
        viewModelOf(::HiddenTextViewModel)
        viewModelOf(::RecentBookViewModel)
        viewModel {
            BookContentDataViewModel(
                bookId = it.get(),
                bookContentUseCase = get()
            )
        }
        viewModel {
            BookDetailViewModel(
                bookId = it.get(),
                bookDetailUseCase = get()
            )
        }
        viewModelOf(::BookChapterContentViewModel)
        viewModel {
            TableOfContentViewModel(
                bookId = it.get(),
                tableOfContentUseCase = get()
            )
        }
        viewModelOf(::BookmarkViewModel)
        viewModel {
            NoteViewModel(
                bookId = it.get(),
                noteRepository = get()
            )
        }
        viewModelOf(::DrawerViewModel)
        viewModelOf(::BookContentStylingViewModel)
        viewModelOf(::BookContentTopBarViewModel)
        viewModelOf(::BookContentBottomBarViewModel)
        viewModelOf(::BottomBarTTSViewModel)
        viewModelOf(::BottomBarAutoScrollViewModel)
        viewModelOf(::AutoScrollViewModel)
        viewModel{
            TTSViewModel(
                isReaderMode = it.get(),
                application = get(),
                ttsManager = get(),
                datastoreUseCase = get(),
                contentUseCase = get(),
                musicUseCase = get()
            )
        }
    }

    val ttsModule = module {
        single<TTSManager>(createdAtStart = true) { TTSManager(context = androidContext()) }
    }
}