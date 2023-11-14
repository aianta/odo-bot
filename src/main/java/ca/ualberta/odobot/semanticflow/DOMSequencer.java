package ca.ualberta.odobot.semanticflow;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.select.NodeVisitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Playground for turning DOMs into sequences for processing with Sabalan algorithm for motif extraction
 */
public class DOMSequencer {

    private static final Logger log = LoggerFactory.getLogger(DOMSequencer.class);

    public static void main(String args []){

        String html = """
                <html class="" dir="ltr" lang="en-CA">
                   <head>
                      <meta charset="utf-8">
                      <link rel="preload" href="/dist/fonts/lato/extended/Lato-Regular-bd03a2cc27.woff2" as="font" type="font/woff2" crossorigin="anonmyous">
                      <link rel="preload" href="/dist/fonts/lato/extended/Lato-Bold-cccb897485.woff2" as="font" type="font/woff2" crossorigin="anonmyous">
                      <link rel="preload" href="/dist/fonts/lato/extended/Lato-Italic-4eb103b4d1.woff2" as="font" type="font/woff2" crossorigin="anonmyous">
                      <link rel="stylesheet" media="screen" href="/dist/brandable_css/no_variables/bundles/fonts-8022a0771c.css">
                      <link rel="shortcut icon" type="image/x-icon" href="/dist/images/favicon-green-19a39adc12.ico">
                      <link rel="apple-touch-icon" href="/dist/images/apple-touch-icon-585e5d997d.png">
                      <link rel="stylesheet" media="all" href="/dist/brandable_css/default/variables-7dd4b80918af0e0218ec0229e4bd5873.css">
                      <link rel="stylesheet" media="all" href="/dist/brandable_css/new_styles_normal_contrast/bundles/common-b3fb5311c1.css">
                      <meta name="viewport" content="width=device-width, initial-scale=1">
                      <meta name="theme-color" content="#394B58">
                      <meta name="robots" content="noindex,nofollow">
                      <link rel="stylesheet" media="all" href="/dist/brandable_css/new_styles_normal_contrast/bundles/quizzes-e8bc583460.css">
                      <link rel="stylesheet" media="all" href="/dist/brandable_css/new_styles_normal_contrast/bundles/learning_outcomes-7c107b21e1.css">
                      <link rel="preload" href="/dist/brandable_css/default/variables-7dd4b80918af0e0218ec0229e4bd5873.js" as="script" type="text/javascript">
                      <link rel="preload" href="/dist/timezone/America/Denver-40670c6af7.js" as="script" type="text/javascript">
                      <link rel="preload" href="/dist/timezone/America/Denver-40670c6af7.js" as="script" type="text/javascript">
                      <link rel="preload" href="/dist/timezone/en_CA-21c9309bc0.js" as="script" type="text/javascript">
                      <link rel="preload" href="/dist/webpack-dev/moment/locale/en-ca-c-159d1bb513.js" as="script" type="text/javascript">
                      <link rel="preload" href="/dist/webpack-dev/main-e-36eadea30b.js" as="script" type="text/javascript" crossorigin="anonymous">
                      <link rel="preload" href="/dist/webpack-dev/quiz_show-c-780d8eabed.js" as="script" type="text/javascript">
                      <link rel="preload" href="/dist/webpack-dev/navigation_header-c-faecaaa8e1.js" as="script" type="text/javascript">
                      <title>Hopefully less mess: Unnamed Course</title>
                      <link rel="stylesheet" href="/dist/brandable_css/no_variables/jst/messageStudentsDialog-f24b5777fd.css" data-loaded-by-brandablecss="true">
                   </head>
                   <body class="with-left-side course-menu-expanded with-right-side quizzes primary-nav-expanded context-course_1 webkit safari ff no-touch">
                      <noscript>
                         <div role="alert" class="ic-flash-static ic-flash-error">
                            <div class="ic-flash__icon" aria-hidden="true">      <i class="icon-warning"></i>    </div>
                            <h1>You need to have JavaScript enabled in order to access this site.</h1>
                         </div>
                      </noscript>
                      <div id="flash_message_holder"></div>
                      <div id="flash_screenreader_holder" role="alert" aria-live="assertive" aria-relevant="additions" class="screenreader-only" aria-atomic="true"></div>
                      <div id="application" class="ic-app">
                         <header id="mobile-header" class="no-print">
                            <button type="button" class="Button Button--icon-action-rev Button--large mobile-header-hamburger">    <i class="icon-solid icon-hamburger"></i>    <span id="mobileHeaderInboxUnreadBadge" class="menu-item__badge" style="min-width: 0; top: 12px; height: 12px; right: 6px; display:none;"></span>    <span class="screenreader-only">Dashboard</span>  </button> \s
                            <div class="mobile-header-space"></div>
                            <a class="mobile-header-title expandable" href="/courses/1" role="button" aria-controls="mobileContextNavContainer">
                               <div>Unnamed</div>
                               <div>Hopefully less mess</div>
                            </a>
                            <a class="Button Button--icon-action-rev Button--large mobile-header-student-view" id="mobile-student-view" aria-label="Student View" role="button" rel="nofollow" data-method="post" href="/courses/1/student_view/1">      <i class="icon-student-view"></i></a>    <button type="button" class="Button Button--icon-action-rev Button--large mobile-header-arrow" aria-label="Navigation Menu">      <i class="icon-arrow-open-down" id="mobileHeaderArrowIcon"></i>    </button>
                         </header>
                         <nav id="mobileContextNavContainer"></nav>
                         <header id="header" class="ic-app-header no-print ">
                            <a href="#content" id="skip_navigation_link">Skip To Content</a> \s
                            <div role="region" class="ic-app-header__main-navigation" aria-label="Global Navigation">
                               <div class="ic-app-header__logomark-container">        <a href="http://localhost:8088/" class="ic-app-header__logomark">          <span class="screenreader-only">Dashboard</span>        </a>      </div>
                               <ul id="menu" class="ic-app-header__menu-list">
                                  <li class="menu-item ic-app-header__menu-list-item ">
                                     <button id="global_nav_profile_link" class="ic-app-header__menu-list-link">
                                        <div class="menu-item-icon-container">
                                           <div aria-hidden="true" class="fs-exclude ic-avatar ">                <img src="http://canvas.instructure.com/images/messages/avatar-50.png" alt="ianta@ualberta.ca">              </div>
                                           <span class="menu-item__badge"></span>           \s
                                        </div>
                                        <div class="menu-item__text">              Account            </div>
                                     </button>
                                  </li>
                                  <li class="menu-item ic-app-header__menu-list-item ">
                                     <button id="global_nav_accounts_link" class="ic-app-header__menu-list-link">
                                        <div class="menu-item-icon-container" aria-hidden="true">
                                           <svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" version="1.1" class="ic-icon-svg ic-icon-svg--accounts" x="0" y="0" viewBox="0 0 200 224" enable-background="new 0 0 200 224" xml:space="preserve"></svg>
                                        </div>
                                        <div class="menu-item__text">              Admin            </div>
                                     </button>
                                  </li>
                                  <li class="ic-app-header__menu-list-item ">
                                     <a id="global_nav_dashboard_link" href="http://localhost:8088/" class="ic-app-header__menu-list-link">
                                        <div class="menu-item-icon-container" aria-hidden="true">
                                           <svg xmlns="http://www.w3.org/2000/svg" class="ic-icon-svg ic-icon-svg--dashboard" version="1.1" x="0" y="0" viewBox="0 0 280 200" enable-background="new 0 0 280 200" xml:space="preserve"></svg>
                                        </div>
                                        <div class="menu-item__text">Dashboard</div>
                                     </a>
                                  </li>
                                  <li class="menu-item ic-app-header__menu-list-item ic-app-header__menu-list-item--active">
                                     <button id="global_nav_courses_link" class="ic-app-header__menu-list-link">
                                        <div class="menu-item-icon-container" aria-hidden="true">
                                           <svg xmlns="http://www.w3.org/2000/svg" class="ic-icon-svg ic-icon-svg--courses" version="1.1" x="0" y="0" viewBox="0 0 280 259" enable-background="new 0 0 280 259" xml:space="preserve"></svg>
                                        </div>
                                        <div class="menu-item__text">              Courses            </div>
                                     </button>
                                  </li>
                                  <li class="menu-item ic-app-header__menu-list-item ">
                                     <a id="global_nav_calendar_link" href="/calendar" class="ic-app-header__menu-list-link">
                                        <div class="menu-item-icon-container" aria-hidden="true">
                                           <svg xmlns="http://www.w3.org/2000/svg" class="ic-icon-svg ic-icon-svg--calendar" version="1.1" x="0" y="0" viewBox="0 0 280 280" enable-background="new 0 0 280 280" xml:space="preserve"></svg>
                                        </div>
                                        <div class="menu-item__text">            Calendar          </div>
                                     </a>
                                  </li>
                                  <li class="menu-item ic-app-header__menu-list-item ">
                                     <a id="global_nav_conversations_link" href="/conversations" class="ic-app-header__menu-list-link">
                                        <div class="menu-item-icon-container">
                                           <span aria-hidden="true">
                                              <svg xmlns="http://www.w3.org/2000/svg" class="ic-icon-svg ic-icon-svg--inbox" version="1.1" x="0" y="0" viewBox="0 0 280 280" enable-background="new 0 0 280 280" xml:space="preserve"></svg>
                                           </span>
                                           <span class="menu-item__badge"></span>         \s
                                        </div>
                                        <div class="menu-item__text">            Inbox          </div>
                                     </a>
                                  </li>
                                  <li class="ic-app-header__menu-list-item">
                                     <a id="global_nav_help_link" role="button" class="ic-app-header__menu-list-link" data-track-category="help system" data-track-label="help button" href="#">
                                        <div class="menu-item-icon-container" role="presentation">
                                           <svg xmlns="http://www.w3.org/2000/svg" class="ic-icon-svg menu-item__icon svg-icon-help" version="1.1" x="0" y="0" viewBox="0 0 200 200" enable-background="new 0 0 200 200" xml:space="preserve" fill="currentColor"></svg>
                                           <span class="menu-item__badge"></span>         \s
                                        </div>
                                        <div class="menu-item__text">            Help          </div>
                                     </a>
                                  </li>
                               </ul>
                            </div>
                            <div class="ic-app-header__secondary-navigation">
                               <ul class="ic-app-header__menu-list">
                                  <li class="menu-item ic-app-header__menu-list-item">
                                     <button id="primaryNavToggle" class="ic-app-header__menu-list-link ic-app-header__menu-list-link--nav-toggle" aria-label="Minimize global navigation" title="Minimize global navigation">
                                        <div class="menu-item-icon-container" aria-hidden="true">
                                           <svg xmlns="http://www.w3.org/2000/svg" class="ic-icon-svg ic-icon-svg--navtoggle" version="1.1" x="0" y="0" width="40" height="32" viewBox="0 0 40 32" xml:space="preserve">  </svg>
                                        </div>
                                     </button>
                                  </li>
                               </ul>
                            </div>
                            <div id="global_nav_tray_container"></div>
                            <div id="global_nav_tour"></div>
                         </header>
                         <div id="instructure_ajax_error_box">
                            <div style="text-align: right; background-color: #fff;"><a href="#" class="close_instructure_ajax_error_box_link">Close</a></div>
                            <iframe id="instructure_ajax_error_result" src="about:blank" style="border: 0;" title="Error"></iframe> \s
                         </div>
                         <div id="wrapper" class="ic-Layout-wrapper">
                            <div class="ic-app-nav-toggle-and-crumbs no-print">
                               <button type="button" id="courseMenuToggle" class="Button Button--link ic-app-course-nav-toggle" aria-live="polite" aria-label="Hide Courses Navigation Menu">            <i class="icon-hamburger" aria-hidden="true"></i>          </button>       \s
                               <div class="ic-app-crumbs">
                                  <nav id="breadcrumbs" role="navigation" aria-label="breadcrumbs">
                                     <ul>
                                        <li class="home"><a href="/"><span class="ellipsible"><i class="icon-home" title="My Dashboard">  <span class="screenreader-only">My Dashboard</span></i></span></a></li>
                                        <li><a href="/courses/1"><span class="ellipsible">Unnamed</span></a></li>
                                        <li><a href="/courses/1/quizzes"><span class="ellipsible">Quizzes</span></a></li>
                                        <li><a href="/courses/1/quizzes/57"><span class="ellipsible">Hopefully less mess</span></a></li>
                                     </ul>
                                  </nav>
                               </div>
                               <div class="right-of-crumbs">            <a class="btn" id="easy_student_view" rel="nofollow" data-method="post" href="/courses/1/student_view/1">              <i class="icon-student-view"></i> Student View</a>        </div>
                            </div>
                            <div id="main" class="ic-Layout-columns">
                               <div class="ic-Layout-watermark"></div>
                               <div id="left-side" class="ic-app-course-menu ic-sticky-on list-view" style="display: block">
                                  <div id="sticky-container" class="ic-sticky-frame">
                                     <nav role="navigation" aria-label="Courses Navigation Menu">
                                        <ul id="section-tabs">
                                           <li class="section"><a href="/courses/1" class="home" tabindex="0">Home</a></li>
                                           <li class="section section-hidden"><a href="/courses/1/announcements" title="No content. Not visible to students" aria-label="Announcements. No content. Not visible to students" class="announcements" data-tooltip="" tabindex="0">Announcements<i class="nav-icon icon-off" aria-hidden="true" role="presentation"></i></a></li>
                                           <li class="section"><a href="/courses/1/assignments" class="assignments" tabindex="0">Assignments</a></li>
                                           <li class="section"><a href="/courses/1/discussion_topics" class="discussions" tabindex="0">Discussions</a></li>
                                           <li class="section"><a href="/courses/1/grades" class="grades" tabindex="0">Grades</a></li>
                                           <li class="section"><a href="/courses/1/users" class="people" tabindex="0">People</a></li>
                                           <li class="section"><a href="/courses/1/wiki" class="pages" tabindex="0">Pages</a></li>
                                           <li class="section section-hidden"><a href="/courses/1/files" title="No content. Not visible to students" aria-label="Files. No content. Not visible to students" class="files" data-tooltip="" tabindex="0">Files<i class="nav-icon icon-off" aria-hidden="true" role="presentation"></i></a></li>
                                           <li class="section"><a href="/courses/1/assignments/syllabus" class="syllabus" tabindex="0">Syllabus</a></li>
                                           <li class="section section-hidden"><a href="/courses/1/outcomes" title="No content. Not visible to students" aria-label="Outcomes. No content. Not visible to students" class="outcomes" data-tooltip="" tabindex="0">Outcomes<i class="nav-icon icon-off" aria-hidden="true" role="presentation"></i></a></li>
                                           <li class="section"><a href="/courses/1/rubrics" class="rubrics" tabindex="0">Rubrics</a></li>
                                           <li class="section"><a href="/courses/1/quizzes" aria-current="page" class="quizzes active" tabindex="0">Quizzes</a></li>
                                           <li class="section section-hidden"><a href="/courses/1/modules" title="No content. Not visible to students" aria-label="Modules. No content. Not visible to students" class="modules" data-tooltip="" tabindex="0">Modules<i class="nav-icon icon-off" aria-hidden="true" role="presentation"></i></a></li>
                                           <li class="section"><a href="/courses/1/settings" class="settings" tabindex="0">Settings</a></li>
                                        </ul>
                                     </nav>
                                  </div>
                               </div>
                               <div id="not_right_side" class="ic-app-main-content">
                                  <div id="content-wrapper" class="ic-Layout-contentWrapper">
                                     <div id="content" class="ic-Layout-contentMain" role="main">
                                        <div id="quiz_show">
                                           <div class="header-bar">
                                              <div class="header-bar-right">
                                                 <div class="header-group-left">        <button id="quiz-publish-link" class="btn quiz-publish-button btn-publish" tabindex="0" aria-pressed="false" title="Publish" aria-label="Unpublished.  Click to publish."><i class="icon-unpublish"></i><span class="publish-text" tabindex="-1">&nbsp;Publish</span><span class="dpd-mount"></span><span class="screenreader-only accessible_label">Unpublished.  Click to publish.</span></button>        <a class="btn" id="preview_quiz_button" preview="1" data-method="post" href="/courses/1/quizzes/57/take?preview=1">Preview</a>      </div>
                                                 <div class="header-group-right">
                                                    <a href="/courses/1/quizzes/57/edit" class="btn edit_assignment_link quiz-edit-button">          <i class="icon-edit"></i> Edit        </a>       \s
                                                    <div class="inline-block">
                                                       <button class=" al-trigger btn" aria-haspopup="true" aria-owns="toolbar-1">            <i class="icon-more" aria-hidden="true"></i>            <span class="screenreader-only">Manage</span>          </button>         \s
                                                       <ul id="toolbar-1" class="al-options" role="menu" tabindex="0" aria-hidden="true" aria-expanded="false">
                                                          <li role="presentation">                <a href="#" rel="/courses/1/assignments/45/rubric" class="show_rubric_link" tabindex="-1" role="menuitem">                  <i class="icon-rubric"><span class="screenreader-only">Show Rubric</span></i>                  Show Rubric                </a>              </li>
                                                          <a href="/courses/1/rubrics" class="icon-rubric" id="add_rubric_url" style="display:none">&nbsp;</a>               \s
                                                          <form class="edit_quiz" id="quiz_lock_form" action="/courses/1/quizzes/57" accept-charset="UTF-8" method="post"><input name="utf8" type="hidden" value="✓"><input type="hidden" name="_method" value="put"><input type="hidden" name="authenticity_token" value="raU5GKvLZiRWsy9PXjf07IemLsg7lcfpYATVMyh0lg/5wxJq4qElVAz0Yw0VToKt18tE+0Hai705VOJrWwD/YQ==">                  <input type="hidden" name="quiz[locked]" id="quiz_locked" value="true"></form>
                                                          <li role="presentation">                  <a href="#" id="lock_this_quiz_now_link" tabindex="-1" role="menuitem">                    <i class="icon-lock"><span class="screenreader-only">Lock this Quiz Now</span></i> Lock this Quiz Now                  </a>                </li>
                                                          <li role="presentation">                <a href="/courses/1/quizzes/57" class="delete_quiz_link" tabindex="-1" role="menuitem">                  <i class="icon-trash"><span class="screenreader-only">Delete</span></i> Delete                </a>              </li>
                                                          <li role="presentation">  <a href="#" class="direct-share-send-to-menu-item">    <i aria-hidden="true" class="icon-user"></i>Send To...  </a></li>
                                                          <li role="presentation">  <a href="#" class="direct-share-copy-to-menu-item">    <i aria-hidden="true" class="icon-duplicate"></i>Copy To...  </a></li>
                                                       </ul>
                                                    </div>
                                                 </div>
                                              </div>
                                           </div>
                                           <header class="quiz-header">
                                              <div class="alert">
                                                 <div class="row-fluid">
                                                    <div class="span8 unpublished_warning">          <strong class="unpublished_quiz_warning">This quiz is unpublished</strong> Only teachers can see the quiz until it is published.        </div>
                                                    <div class="span4 actions">        </div>
                                                 </div>
                                              </div>
                                              <h1 id="quiz_title">      Hopefully less mess    </h1>
                                              <div class="row-fluid">    </div>
                                              <div class="row-fluid">
                                                 <form class="form-horizontal bootstrap-form display-only" style="margin-top:18px;">
                                                    <fieldset>
                                                       <div class="control-group">
                                                          <div class="control-label">        Quiz Type      </div>
                                                          <div class="controls">        <span class="value">Graded Quiz</span>      </div>
                                                       </div>
                                                       <div class="control-group">
                                                          <div class="control-label">        Points      </div>
                                                          <div class="controls">        <span class="value">_</span>      </div>
                                                       </div>
                                                       <div class="control-group">
                                                          <div class="control-label">          Assignment Group        </div>
                                                          <div class="controls">          <span class="value">Assignments</span>        </div>
                                                       </div>
                                                       <div class="control-group">
                                                          <div class="control-label">        Shuffle Answers      </div>
                                                          <div class="controls">        <span class="value">          No        </span>      </div>
                                                       </div>
                                                       <div class="control-group">
                                                          <div class="control-label">        Time Limit      </div>
                                                          <div class="controls">        <span class="value">            No Time Limit        </span>      </div>
                                                       </div>
                                                       <div class="control-group">
                                                          <div class="control-label">        Multiple Attempts      </div>
                                                          <div class="controls">        <span class="value">          No        </span>      </div>
                                                       </div>
                                                       <div class="control-group">
                                                          <div class="control-label">        View Responses      </div>
                                                          <div class="controls">        <span class="value">          Always        </span>      </div>
                                                       </div>
                                                       <div class="control-group">
                                                          <div class="control-label">          Show Correct Answers        </div>
                                                          <div class="controls">          <span class="value">            Immediately          </span>        </div>
                                                       </div>
                                                       <div class="control-group">
                                                          <div class="control-label">        One Question at a Time      </div>
                                                          <div class="controls">        <span class="value">          No        </span>      </div>
                                                       </div>
                                                       <div class="control-group" style="display: none;">
                                                          <div class="control-label">        Anonymous Submissions      </div>
                                                          <div class="controls">        <span class="value">          No        </span>      </div>
                                                       </div>
                                                    </fieldset>
                                                 </form>
                                                 <table class="ic-Table assignment_dates">
                                                    <thead>
                                                       <tr>
                                                          <th scope="col">Due</th>
                                                          <th scope="col">For</th>
                                                          <th scope="col">Available from</th>
                                                          <th scope="col">Until</th>
                                                       </tr>
                                                    </thead>
                                                    <tbody>
                                                       <tr>
                                                          <td>        <span aria-hidden="true">-</span>        <span class="screenreader-only">N/A</span>      </td>
                                                          <td>Everyone</td>
                                                          <td>        <span aria-hidden="true">-</span>        <span class="screenreader-only">N/A</span>      </td>
                                                          <td>        <span aria-hidden="true">-</span>        <span class="screenreader-only">N/A</span>      </td>
                                                       </tr>
                                                    </tbody>
                                                 </table>
                                              </div>
                                              <div class="preview_quiz_button">        <a class="btn btn-primary" id="preview_quiz_button" preview="1" data-method="post" href="/courses/1/quizzes/57/take?preview=1">Preview</a>      </div>
                                              <div id="quiz-submission-version-table"></div>
                                           </header>
                                           <div id="direct-share-mount-point"></div>
                                           <div id="assignment_external_tools">
                                              <div></div>
                                           </div>
                                           <div id="module_sequence_footer"></div>
                                        </div>
                                     </div>
                                  </div>
                                  <div id="right-side-wrapper" class="ic-app-main-content__secondary">
                                     <aside id="right-side" role="complementary">
                                        <div id="sidebar_content" class="rs-margin-bottom">
                                           <ul class="page-action-list" style="display:none;">
                                              <h2>Related Items</h2>
                                           </ul>
                                        </div>
                                     </aside>
                                  </div>
                               </div>
                            </div>
                         </div>
                         <div style="display:none;">
                            <!-- Everything inside of this should always stay hidden -->   \s
                         </div>
                         <div id="aria_alerts" class="hide-text affix" role="alert" aria-live="assertive"></div>
                         <div id="StudentTray__Container"></div>
                      </div>
                      <!-- #application -->
                      <div id="nav-tray-portal" style="position: relative; z-index: 99;"></div>
                      <div class="tinymce-a11y-checker-container"></div>
                   </body>
                </html>
                                """;

        Document doc = Jsoup.parse(html);

        DOMVisitor visitor = new DOMVisitor();

        doc.traverse(visitor);

        log.info("parsed!");

        log.info("Sequence: {}",visitor.getSequence() );

    }

    static class DOMVisitor implements NodeVisitor{

        private StringBuilder sb = new StringBuilder();

        public DOMVisitor(){
            sb.append("|");
        }

        @Override
        public void head(Node node, int depth) {
            if (node instanceof Element){
                Element element = (Element) node;

                sb.append(element.tagName() + (element.className().isEmpty()?"":"["+element.className()+"]") + "|");
            }
        }

        public String getSequence(){
            return sb.toString();
        }
    }

}
